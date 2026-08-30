package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.config.HttpDataSourceConfig;
import com.example.edgegateway.config.MysqlDataSourceConfig;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.DataSourceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceServiceTest {

    private DataSourceMapper mapper;
    private DataSourceManager manager;
    private ActionMapper actionMapper;
    private JobMapper jobMapper;
    private RouteRegistry routeRegistry;
    private DataSourceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DataSourceMapper.class);
        manager = mock(DataSourceManager.class);
        actionMapper = mock(ActionMapper.class);
        jobMapper = mock(JobMapper.class);
        routeRegistry = mock(RouteRegistry.class);
        service = new DataSourceService(mapper, manager, actionMapper, jobMapper, routeRegistry);
    }

    private DataSourceRequest request(String name, DataSourceType type, DataSourceConfig addition) {
        return new DataSourceRequest(name, 1L, type, "http://localhost:8080/mock", addition);
    }

    @Test
    void createsValidDataSource() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(DataSource.class))).thenAnswer(inv -> {
            inv.getArgument(0, DataSource.class).setId(1L);
            return 1;
        });

        DataSource saved = service.create(request("erp", DataSourceType.HTTP, new HttpDataSourceConfig(null)));

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getName()).isEqualTo("erp");
        assertThat(saved.getType()).isEqualTo(DataSourceType.HTTP);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create(request("", DataSourceType.HTTP, new HttpDataSourceConfig(null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRequiredAdditionField() {
        assertThatThrownBy(() -> service.create(request("mysql", DataSourceType.MYSQL,
                new MysqlDataSourceConfig("sa", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void rejectsDuplicateNameInSite() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("dup", DataSourceType.HTTP, new HttpDataSourceConfig(null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteInvalidatesPoolAfterRemoval() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setSiteId(1L);
        ds.setName("erp");
        ds.setType(DataSourceType.HTTP);
        when(mapper.selectById(1L)).thenReturn(ds);

        service.delete(1L);

        verify(mapper).deleteById(1L);
        verify(manager).invalidate(1L);
    }

    @Test
    void updateInvalidatesPoolWhenConnectionParamsChange() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setSiteId(1L);
        ds.setName("erp");
        ds.setType(DataSourceType.HTTP);
        when(mapper.selectById(1L)).thenReturn(ds);
        when(mapper.selectCount(any())).thenReturn(0L);

        service.update(1L, request("erp", DataSourceType.HTTP, new HttpDataSourceConfig(null)));

        verify(manager).invalidate(1L);
    }

    @Test
    void updateRebuildsEnabledJobsReferencingDataSource() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setSiteId(1L);
        ds.setName("mysql");
        ds.setType(DataSourceType.MYSQL);
        when(mapper.selectById(1L)).thenReturn(ds);
        when(mapper.selectCount(any())).thenReturn(0L);
        Job job = enabledJob(5L);
        when(jobMapper.selectById(5L)).thenReturn(job);
        when(actionMapper.selectList(any())).thenReturn(List.of(readAction(5L, 1L)));

        service.update(1L, request("mysql", DataSourceType.MYSQL, new MysqlDataSourceConfig("u", "p")));

        verify(manager).invalidate(1L);
        verify(routeRegistry).enable(5L);
    }

    @Test
    void deleteDisablesEnabledJobsReferencingDataSource() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setSiteId(1L);
        ds.setName("mysql");
        ds.setType(DataSourceType.MYSQL);
        when(mapper.selectById(1L)).thenReturn(ds);
        Job job = enabledJob(5L);
        when(jobMapper.selectById(5L)).thenReturn(job);
        when(actionMapper.selectList(any())).thenReturn(List.of(readAction(5L, 1L)));

        service.delete(1L);

        verify(manager).invalidate(1L);
        verify(routeRegistry).disable(5L);
        assertThat(job.getStatus()).isEqualTo(0);
    }

    private Job enabledJob(Long id) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(1);
        return job;
    }

    private Action readAction(Long jobId, Long dataSourceId) {
        Action action = new Action();
        action.setJobId(jobId);
        action.setType(ActionKind.READ_SQL);
        action.setAddition(Map.of("dataSourceId", dataSourceId, "sql", "select 1"));
        return action;
    }
}
