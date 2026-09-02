package com.example.edgegateway.service;

import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleType;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** 调度编译器：友好配置 → fixedRate 毫秒 / Spring cron 表达式（内部表达）。 */
class ScheduleCompilerTest {

    private final ScheduleCompiler compiler = new ScheduleCompiler();

    private Job job(ScheduleType type) {
        Job job = new Job();
        job.setId(1L);
        job.setScheduleType(type);
        return job;
    }

    @Test
    void noneOrNullIsNotRecurring() {
        assertThat(compiler.recurring(job(ScheduleType.NONE))).isFalse();
        assertThat(compiler.recurring(job(null))).isFalse();
        assertThat(compiler.interval(job(ScheduleType.NONE))).isFalse();
        assertThat(compiler.cron(job(ScheduleType.NONE))).isNull();
    }

    @Test
    void intervalMinutesCompilesToFixedRateMs() {
        Job job = job(ScheduleType.INTERVAL);
        job.setIntervalValue(5);
        job.setIntervalUnit("MINUTES");
        assertThat(compiler.interval(job)).isTrue();
        assertThat(compiler.fixedRateMs(job)).isEqualTo(5L * 60_000L);
        assertThat(compiler.cron(job)).isNull();
    }

    @Test
    void intervalHoursCompilesToFixedRateMs() {
        Job job = job(ScheduleType.INTERVAL);
        job.setIntervalValue(2);
        job.setIntervalUnit("HOURS");
        assertThat(compiler.fixedRateMs(job)).isEqualTo(2L * 3_600_000L);
    }

    @Test
    void nonIntervalHasNoFixedRate() {
        assertThatIllegalStateException()
                .isThrownBy(() -> compiler.fixedRateMs(job(ScheduleType.DAILY)))
                .withMessageContaining("只有 INTERVAL");
    }

    @Test
    void dailyCompilesToCron() {
        Job job = job(ScheduleType.DAILY);
        job.setScheduleTime("08:30");
        assertThat(compiler.cron(job)).isEqualTo("0 30 8 * * *");
    }

    @Test
    void weeklyCompilesToCronWithWeekday() {
        Job job = job(ScheduleType.WEEKLY);
        job.setScheduleWeekday(5);
        job.setScheduleTime("18:05");
        assertThat(compiler.cron(job)).isEqualTo("0 5 18 * * 5");
    }

    @Test
    void zoneFallsBackToSystemDefaultWhenBlank() {
        Job job = job(ScheduleType.DAILY);
        assertThat(compiler.zone(job)).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void zoneUsesConfiguredTimezone() {
        Job job = job(ScheduleType.DAILY);
        job.setTimezone("Asia/Shanghai");
        assertThat(compiler.zone(job)).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }
}
