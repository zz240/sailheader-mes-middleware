package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionType;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.ActionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionServiceTest {

    private ActionMapper actionMapper;
    private JobMapper jobMapper;
    private DataSourceMapper dataSourceMapper;
    private MappingHistoryService mappingHistoryService;
    private ActionService service;

    @BeforeEach
    void setUp() {
        actionMapper = mock(ActionMapper.class);
        jobMapper = mock(JobMapper.class);
        dataSourceMapper = mock(DataSourceMapper.class);
        mappingHistoryService = mock(MappingHistoryService.class);
        service = new ActionService(actionMapper, jobMapper, dataSourceMapper, mappingHistoryService);
    }

    private ActionRequest request(Integer index, Map<String, Object> addition) {
        return new ActionRequest("read", ActionType.REMOTE_ACCESS, index, addition);
    }

    @Test
    void addsActionToExistingJob() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenAnswer(inv -> {
            inv.getArgument(0, Action.class).setId(1L);
            return 1;
        });

        Action saved = service.add(1L, request(1, Map.of()));

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getJobId()).isEqualTo(1L);
        assertThat(saved.getType()).isEqualTo(ActionType.REMOTE_ACCESS);
    }

    @Test
    void rejectsActionWhenJobMissing() {
        when(jobMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.add(99L, request(1, Map.of())))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rejectsDuplicateIndexWithinJob() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.add(1L, request(1, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
    }

    @Test
    void rejectsUnknownDataSourceReference() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(dataSourceMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.add(1L, request(1, Map.of("dataSourceId", 99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据源");
    }

    @Test
    void acceptsKnownDataSourceReference() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());
        when(actionMapper.insert(any(Action.class))).thenReturn(1);

        Action saved = service.add(1L, request(1, Map.of("dataSourceId", 7)));

        assertThat(saved.getJobId()).isEqualTo(1L);
    }

    @Test
    void rejectsEmptyFieldMappingRules() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);

        ActionRequest request = new ActionRequest("map", ActionType.FIELD_MAPPING, 2, Map.of());

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("映射规则");
    }

    @Test
    void fieldMappingSaveRecordsMappingHistory() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);

        ActionRequest request = new ActionRequest("map", ActionType.FIELD_MAPPING, 2,
                Map.of("rules", java.util.List.of(Map.of(
                        "source", "order_no", "target", "sheet_no", "transform", "STRING"))));

        service.add(1L, request);

        verify(mappingHistoryService).recordFromAction(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void remoteAccessSaveDoesNotRecordMappingHistory() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());

        service.add(1L, request(1, Map.of("dataSourceId", 7)));

        verify(mappingHistoryService, never()).recordFromAction(any(), any());
    }
}
