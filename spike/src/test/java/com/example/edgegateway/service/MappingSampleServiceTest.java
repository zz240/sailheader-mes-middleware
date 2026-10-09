package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.MappingSampleMapper;
import com.example.edgegateway.model.MappingSample;
import com.example.edgegateway.model.MappingSampleKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 粘进来的那份样例怎么落库（#119）：存**原文**、一格一份；保存那一刻手里是探测来的就把这一格删掉。
 * 读回来那几个断言（打开时「存过就用存的那份」）在 {@link FieldMappingEditorServiceTest} 里。
 */
class MappingSampleServiceTest {

    private MappingSampleMapper mapper;
    private ActionMapper actionMapper;
    private MappingSampleService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MappingSampleMapper.class);
        actionMapper = mock(ActionMapper.class);
        service = new MappingSampleService(mapper, actionMapper);
    }

    /** 第一行就是原文本身：不存推出来的字段清单（清单是原文的纯函数，原文还能给试算用）。 */
    @Test
    void storesTheRawTextInItsOwnCell() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.save(3L, MappingSampleKind.SOURCE, 1L, "{\"order_no\":\"PO-1\"}");

        ArgumentCaptor<MappingSample> inserted = ArgumentCaptor.forClass(MappingSample.class);
        verify(mapper).insert(inserted.capture());
        MappingSample row = inserted.getValue();
        assertThat(row.getJobId()).isEqualTo(3L);
        assertThat(row.getKind()).isEqualTo(MappingSampleKind.SOURCE);
        assertThat(row.getRefId()).isEqualTo(1L);
        assertThat(row.getSampleJson()).isEqualTo("{\"order_no\":\"PO-1\"}");
        assertThat(row.getUpdatedAt()).isNotNull();
    }

    /** 同一格再存一次是覆盖（唯一键那三格：任务 + 用途 + 归属），不是又插一行。 */
    @Test
    void overwritesTheSameCellInsteadOfAddingARow() {
        MappingSample existing = cell(7L, 3L, MappingSampleKind.SOURCE, 1L, "{\"old\":1}");
        when(mapper.selectOne(any())).thenReturn(existing);

        service.save(3L, MappingSampleKind.SOURCE, 1L, "{\"order_no\":\"PO-1\"}");

        assertThat(existing.getSampleJson()).isEqualTo("{\"order_no\":\"PO-1\"}");
        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(MappingSample.class));
    }

    /** 手里是探测来的（没有原文）→ 把这一格删掉：下回打开就回到探测那条路（#119 验收三）。 */
    @Test
    void clearsTheCellWhenTheSampleInHandWasProbed() {
        service.save(3L, MappingSampleKind.SOURCE, 1L, null);

        verify(mapper).delete(any());
        verify(mapper, never()).insert(any(MappingSample.class));
    }

    @Test
    void treatsBlankTextAsNoSample() {
        service.save(3L, MappingSampleKind.TARGET, 13L, "   ");

        verify(mapper).delete(any());
        verify(mapper, never()).insert(any(MappingSample.class));
    }

    @Test
    void readsBackTheStoredText() {
        when(mapper.selectOne(any())).thenReturn(cell(7L, 3L, MappingSampleKind.TARGET, 13L, "{\"a\":1}"));

        assertThat(service.stored(3L, MappingSampleKind.TARGET, 13L)).isEqualTo("{\"a\":1}");
    }

    @Test
    void nothingStoredReadsBackAsNull() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(service.stored(3L, MappingSampleKind.SOURCE, 2L)).isNull();
    }

    /** 删动作：目标那格按动作 id、来源那格按动作序号，两格都带走（#119「删动作时顺手删掉它的样例行」）。 */
    @Test
    void clearsBothCellsOfADeletedAction() {
        service.clearOfAction(3L, 13L, 4);

        verify(mapper, times(2)).delete(any());
    }

    /** 动作没排上序号（理论上不会有）时只清目标那一格，不能因为一格缺归属就整段炸掉。 */
    @Test
    void clearsWhatItCanOfAnActionWithoutAnIndex() {
        service.clearOfAction(3L, 13L, null);

        verify(mapper, times(1)).delete(any());
    }

    @Test
    void clearsEveryCellOfADeletedJob() {
        service.clearOfJob(3L);

        verify(mapper).delete(any());
        verify(actionMapper).delete(any()); // 任务删除后，它下面的动作行也一并删除（不保留残留行）
    }

    /** 没给任务 id 就什么都不删（调用方传空不该误伤全表）。 */
    @Test
    void clearsNothingWithoutAJobId() {
        service.clearOfJob(null);

        verifyNoInteractions(mapper, actionMapper);
    }

    /** 一格的身份缺一不可：缺了就是调用方的错，不能静默写出一行谁都认不出来的样例。 */
    @Test
    void rejectsACellWithoutItsOwnership() {
        assertThatThrownBy(() -> service.save(null, MappingSampleKind.SOURCE, 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归属");
        assertThatThrownBy(() -> service.save(3L, null, 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(3L, MappingSampleKind.SOURCE, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.stored(3L, MappingSampleKind.TARGET, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MappingSample cell(Long id, Long jobId, MappingSampleKind kind, Long refId, String json) {
        MappingSample row = new MappingSample();
        row.setId(id);
        row.setJobId(jobId);
        row.setKind(kind);
        row.setRefId(refId);
        row.setSampleJson(json);
        return row;
    }
}
