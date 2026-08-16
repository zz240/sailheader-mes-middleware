package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.spi.RouteController;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteRegistryTest {

    private final CamelContext camelContext = mock(CamelContext.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final RouteGenerator routeGenerator = mock(RouteGenerator.class);
    private final RouteRegistry registry =
            new RouteRegistry(camelContext, jobMapper, actionMapper, routeGenerator);
    private final RouteController routeController = mock(RouteController.class);

    @Test
    void enableRegistersRoute() throws Exception {
        Job job = new Job();
        job.setId(1L);
        when(camelContext.getRoute("job-1")).thenReturn(null);
        when(camelContext.getRouteController()).thenReturn(routeController);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectList(any())).thenReturn(List.of());
        RouteBuilder builder = new RouteBuilder() {
            @Override
            public void configure() {
            }
        };
        when(routeGenerator.build(any(Job.class), anyList())).thenReturn(builder);

        registry.enable(1L);

        verify(camelContext).addRoutes(builder);
    }

    @Test
    void disableStopsAndRemovesRoute() throws Exception {
        when(camelContext.getRoute("job-1")).thenReturn(mock(Route.class));
        when(camelContext.getRouteController()).thenReturn(routeController);

        registry.disable(1L);

        verify(routeController).stopRoute("job-1");
        verify(camelContext).removeRoute("job-1");
    }

    @Test
    void reloadSkipsJobThatFailsToGenerate() {
        Job bad = new Job();
        bad.setId(1L);
        when(jobMapper.selectList(any())).thenReturn(List.of(bad));
        when(actionMapper.selectList(any())).thenReturn(List.of());
        when(routeGenerator.build(any(Job.class), anyList()))
                .thenThrow(new IllegalArgumentException("任务没有动作，无法生成路由"));

        // 单个任务配置有问题不应拖垮启动
        registry.reloadEnabledJobs();
    }
}
