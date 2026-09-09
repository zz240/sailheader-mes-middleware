package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RouteLifecycle 是“启用/重建失败 → 自动停用 + 摘除路由/调度 + 写 lastError”的策略 seam。 */
class RouteLifecycleTest {

    private JobMapper jobMapper;
    private RouteRegistry routeRegistry;
    private RouteLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        jobMapper = mock(JobMapper.class);
        routeRegistry = mock(RouteRegistry.class);
        lifecycle = new RouteLifecycle(jobMapper, routeRegistry);
    }

    private Job job(int status) {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(status);
        return job;
    }

    @Test
    void disabledJobDoesNotRebuild() {
        Job job = job(0);
        when(jobMapper.selectById(1L)).thenReturn(job);

        lifecycle.rebuildIfEnabled(1L);

        verify(routeRegistry, never()).enable(1L);
    }

    @Test
    void missingJobDoesNotRebuild() {
        when(jobMapper.selectById(1L)).thenReturn(null);

        lifecycle.rebuildIfEnabled(1L);

        verify(routeRegistry, never()).enable(1L);
    }

    @Test
    void enabledJobRebuilds() {
        Job job = job(1);
        when(jobMapper.selectById(1L)).thenReturn(job);

        lifecycle.rebuildIfEnabled(1L);

        verify(routeRegistry).enable(1L);
    }

    @Test
    void rebuildFailureAutoDisablesAndThrows() {
        Job job = job(1);
        when(jobMapper.selectById(1L)).thenReturn(job);
        doThrow(new IllegalStateException("路由编译失败")).when(routeRegistry).enable(1L);

        assertThatThrownBy(() -> lifecycle.rebuildIfEnabled(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已自动停用");
        assertThat(job.getStatus()).isEqualTo(0);
        verify(jobMapper).updateById(job);
    }

    @Test
    void rebuildFailureWritesLastErrorAndTearsDownRoutes() {
        Job job = job(1);
        when(jobMapper.selectById(1L)).thenReturn(job);
        doThrow(new IllegalArgumentException("动作链 sourceStep 引用不一致: sourceStep=5 在动作链中不存在"))
                .when(routeRegistry).enable(1L);

        assertThatThrownBy(() -> lifecycle.rebuildIfEnabled(1L))
                .isInstanceOf(IllegalArgumentException.class);

        // 定位信息必须落到 job.lastError，供列表/详情直接看到原因
        assertThat(job.getLastError()).contains("sourceStep=5");
        // 停用后必须摘掉残留路由/调度，否则旧链会继续按老配置跑
        verify(routeRegistry).disable(1L);
    }

    @Test
    void enableFailureAutoDisablesAndWritesLastError() {
        Job job = job(1);
        when(jobMapper.selectById(1L)).thenReturn(job);
        doThrow(new IllegalArgumentException("动作链 sourceStep 引用不一致: sourceStep=5 在动作链中不存在"))
                .when(routeRegistry).enable(1L);

        assertThatThrownBy(() -> lifecycle.enable(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已自动停用");
        assertThat(job.getStatus()).isEqualTo(0);
        assertThat(job.getLastError()).contains("sourceStep=5");
        verify(jobMapper).updateById(job);
        verify(routeRegistry).disable(1L);
    }

    @Test
    void enableSuccessDoesNotTouchStatus() {
        Job job = job(1);
        when(jobMapper.selectById(1L)).thenReturn(job);

        lifecycle.enable(1L);

        verify(routeRegistry).enable(1L);
        verify(jobMapper, never()).updateById(any(Job.class));
    }

    // ---------- 启动重载（#89：历史遗留的悬空引用自动停用） ----------

    @Test
    void reloadAutoDisablesJobWithInconsistentChain() {
        Job job = job(1);
        when(jobMapper.selectList(any())).thenReturn(List.of(job));
        doThrow(new IllegalArgumentException("动作链 sourceStep 引用不一致: sourceStep=1 在动作链中不存在"))
                .when(routeRegistry).enable(1L);

        lifecycle.reloadEnabledJobs();

        assertThat(job.getStatus()).isEqualTo(0);
        assertThat(job.getLastError()).contains("sourceStep=1");
        verify(routeRegistry).disable(1L);
    }

    @Test
    void reloadKeepsJobEnabledWhenRouteBuildFailsForOtherReasons() {
        Job job = job(1);
        when(jobMapper.selectList(any())).thenReturn(List.of(job));
        doThrow(new IllegalStateException("注册路由失败: 1")).when(routeRegistry).enable(1L);

        // 启动期瞬时问题（如 broker 不可达）不批量停用，也不拖垮启动
        lifecycle.reloadEnabledJobs();

        assertThat(job.getStatus()).isEqualTo(1);
        assertThat(job.getLastError()).isNull();
    }
}
