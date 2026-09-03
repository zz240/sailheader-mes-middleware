package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RouteLifecycle 是“配置变更后按启用状态重建路由/调度 + 失败自动停用”的策略 seam。 */
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

    @Test
    void disabledJobDoesNotRebuild() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(0);
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
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        when(jobMapper.selectById(1L)).thenReturn(job);

        lifecycle.rebuildIfEnabled(1L);

        verify(routeRegistry).enable(1L);
    }

    @Test
    void rebuildFailureAutoDisablesAndThrows() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        when(jobMapper.selectById(1L)).thenReturn(job);
        doThrow(new IllegalStateException("路由编译失败")).when(routeRegistry).enable(1L);

        assertThatThrownBy(() -> lifecycle.rebuildIfEnabled(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已自动停用");
        assertThat(job.getStatus()).isEqualTo(0);
        verify(jobMapper).updateById(job);
    }
}
