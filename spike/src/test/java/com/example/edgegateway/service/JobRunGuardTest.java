package com.example.edgegateway.service;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务串行（#146）的判据：同一个任务同一时刻只允许一个运行占位，释放与回填按「谁占用的谁说了算」。
 * 这几条正是本票最容易写错的边界（占位非原子、过期凭据误放新占用、runId 回填到别人的占用上）。
 */
class JobRunGuardTest {

    private final JobRunGuard guard = new JobRunGuard();

    @Test
    void acquiresWhenJobIsIdle() {
        JobRunGuard.Handle handle = guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL);

        assertThat(handle).isNotNull();
        assertThat(handle.jobId()).isEqualTo(7L);
        assertThat(handle.origin()).isEqualTo(JobRunGuard.ORIGIN_MANUAL);
        assertThat(handle.runId()).isNull();
        assertThat(guard.isRunning(7L)).isTrue();
    }

    @Test
    void rejectsSecondAcquireForSameJob() {
        assertThat(guard.tryAcquire(7L, JobRunGuard.ORIGIN_SCHEDULE)).isNotNull();

        assertThat(guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL)).isNull();
    }

    @Test
    void differentJobsDoNotBlockEachOther() {
        assertThat(guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL)).isNotNull();
        assertThat(guard.tryAcquire(8L, JobRunGuard.ORIGIN_MANUAL)).isNotNull();

        assertThat(guard.isRunning(7L)).isTrue();
        assertThat(guard.isRunning(8L)).isTrue();
    }

    @Test
    void acquiresAgainAfterRelease() {
        JobRunGuard.Handle first = guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL);
        guard.release(first);

        assertThat(guard.isRunning(7L)).isFalse();
        assertThat(guard.tryAcquire(7L, JobRunGuard.ORIGIN_SCHEDULE)).isNotNull();
    }

    @Test
    void staleHandleDoesNotReleaseNewOccupancy() {
        JobRunGuard.Handle stale = guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL);
        guard.release(stale);
        guard.tryAcquire(7L, JobRunGuard.ORIGIN_SCHEDULE);

        // 上一次运行的 finally 迟到时，不能把后一次刚拿到的占用删掉
        guard.release(stale);

        assertThat(guard.isRunning(7L)).isTrue();
    }

    @Test
    void releaseOfForeignHandleIsNoop() {
        JobRunGuard.Handle seven = guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL);
        JobRunGuard.Handle eight = guard.tryAcquire(8L, JobRunGuard.ORIGIN_MANUAL);

        guard.release(null);
        guard.release(eight);

        assertThat(seven.jobId()).isEqualTo(7L);
        assertThat(guard.isRunning(7L)).isTrue();
        assertThat(guard.isRunning(8L)).isFalse();
    }

    @Test
    void bindsRunIdToCurrentOccupancy() {
        JobRunGuard.Handle handle = guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL);

        JobRunGuard.bindRun(guard, 7L, 2837L);
        assertThat(handle.runId()).isEqualTo(2837L);
        assertThat(guard.describe(handle)).contains("run=2837").contains("origin=manual");
    }

    @Test
    void doesNotBindRunIdWhenThisThreadHoldsNoOccupancy() throws Exception {
        JobRunGuard.Handle holder = guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL);
        assertThat(holder).isNotNull();

        // 另一个线程没有这次占用的凭据（如走断点续跑那条路的线程），不得把运行标识写到别人的占用上
        Thread other = new Thread(() -> JobRunGuard.bindRun(guard, 7L, 2837L));
        other.start();
        other.join();

        assertThat(holder.runId()).isNull();
        // 凭据仍归占用它的那条线程（这里是主线程自己）
        assertThat(guard.current()).isSameAs(holder);
        assertThat(guard.isRunning(7L)).isTrue();
    }

    @Test
    void doesNotBindRunIdForUnknownJob() {
        JobRunGuard.bindRun(guard, 9L, 2837L);

        assertThat(guard.isRunning(9L)).isFalse();
        assertThat(guard.describe(null)).isEqualTo("未被占用");
    }

    @Test
    void onlyOneAcquireSucceedsUnderConcurrency() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<JobRunGuard.Handle>> attempts = Collections.nCopies(threads,
                    () -> guard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL));
            long acquired = 0;
            for (Future<JobRunGuard.Handle> result : pool.invokeAll(attempts)) {
                if (result.get() != null) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
