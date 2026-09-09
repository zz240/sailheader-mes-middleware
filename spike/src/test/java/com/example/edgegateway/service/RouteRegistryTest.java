package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.spi.RouteController;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteRegistryTest {

    private final CamelContext camelContext = mock(CamelContext.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final RouteGenerator routeGenerator = mock(RouteGenerator.class);
    private final JobScheduler jobScheduler = mock(JobScheduler.class);
    private final RouteRegistry registry =
            new RouteRegistry(camelContext, jobMapper, actionMapper, routeGenerator, jobScheduler);
    private final RouteController routeController = mock(RouteController.class);

    @Test
    void enableRegistersRoute() throws Exception {
        Job job = new Job();
        job.setId(1L);
        when(camelContext.getRoute(anyString())).thenReturn(null);
        when(camelContext.getRouteController()).thenReturn(routeController);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectList(any())).thenReturn(List.of());
        RouteBuilder builder = new RouteBuilder() {
            @Override
            public void configure() {
            }
        };
        when(routeGenerator.build(any(Job.class), anyList())).thenReturn(List.of(builder));

        registry.enable(1L);

        verify(camelContext).addRoutes(builder);
        verify(jobScheduler).reconcile(1L);
    }

    @Test
    void disableStopsAndRemovesRoute() throws Exception {
        when(camelContext.getRoute("job-1")).thenReturn(mock(Route.class));
        when(camelContext.getRoute("direct-1")).thenReturn(mock(Route.class));
        when(camelContext.getRouteController()).thenReturn(routeController);

        registry.disable(1L);

        verify(routeController).stopRoute("job-1");
        verify(routeController).stopRoute("direct-1");
        verify(camelContext).removeRoute("job-1");
        verify(camelContext).removeRoute("direct-1");
        verify(jobScheduler).reconcile(1L);
    }

    // ---------- 启用前 sourceStep 引用一致性（#89 T3b） ----------

    private Action fieldMap(int index, int sourceStep) {
        Action action = new Action();
        action.setName("map");
        action.setType(ActionKind.FIELD_MAP);
        action.setActionIndex(index);
        action.setAddition(Map.of("rules", List.of(Map.of(
                "sourceStep", sourceStep, "source", "order_no", "target", "sheet_no", "transform", "STRING"))));
        return action;
    }

    @Test
    void enableRejectsDanglingMappingSourceStep() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        // 链上只剩这条 FIELD_MAP：sourceStep=1 指向的动作已被删除
        when(actionMapper.selectList(any())).thenReturn(List.of(fieldMap(2, 1)));

        assertThatThrownBy(() -> registry.enable(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStep=1")
                .hasMessageContaining("不存在");
        verify(routeGenerator, never()).build(any(Job.class), anyList());
    }
}
