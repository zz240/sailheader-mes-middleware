package com.example.edgegateway.service;

import com.example.edgegateway.mapper.TemplateMapper;
import com.example.edgegateway.model.Template;
import com.example.edgegateway.web.dto.TemplateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateServiceTest {

    private TemplateMapper templateMapper;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        templateMapper = mock(TemplateMapper.class);
        service = new TemplateService(templateMapper);
        when(templateMapper.selectCount(any())).thenReturn(0L);
    }

    private TemplateRequest request(String name, String version, Map<String, Object> config) {
        return new TemplateRequest(name, version, config);
    }

    @Test
    void createDefaultsToVersion1ThenIncrements() {
        Map<String, Object> config = Map.of("job", Map.of("name", "po-sync"), "actions", List.of());
        when(templateMapper.selectList(any())).thenReturn(List.of());
        when(templateMapper.insert(any(Template.class))).thenAnswer(inv -> {
            inv.getArgument(0, Template.class).setId(1L);
            return 1;
        });

        Template first = service.create(request("po-sync", null, config));
        assertThat(first.getVersion()).isEqualTo("1");

        Template existing = new Template();
        existing.setName("po-sync");
        existing.setVersion("1");
        when(templateMapper.selectList(any())).thenReturn(List.of(existing));

        Template second = service.create(request("po-sync", null, config));
        assertThat(second.getVersion()).isEqualTo("2");
    }

    @Test
    void createRejectsDuplicateNameVersion() {
        when(templateMapper.selectList(any())).thenReturn(List.of());
        when(templateMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("po-sync", "1", Map.of("a", 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void importCreatesNewVersionWithoutOverwriting() {
        Map<String, Object> config = Map.of("job", Map.of("name", "po-sync"), "actions", List.of());
        Template existing = new Template();
        existing.setName("po-sync");
        existing.setVersion("1");
        when(templateMapper.selectList(any())).thenReturn(List.of(existing));
        when(templateMapper.insert(any(Template.class))).thenReturn(1);

        Template imported = service.importTemplate(request("po-sync", null, config));

        assertThat(imported.getVersion()).isEqualTo("2");
        assertThat(imported.getConfigJson()).isEqualTo(config);
    }

    @Test
    void getThrowsWhenMissing() {
        when(templateMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void updateChangesConfigJson() {
        Template template = new Template();
        template.setId(1L);
        template.setName("po-sync");
        template.setVersion("1");
        template.setConfigJson(Map.of("old", true));
        when(templateMapper.selectById(1L)).thenReturn(template);

        Template updated = service.update(1L, request("po-sync", null, Map.of("new", true)));

        assertThat(updated.getConfigJson()).containsEntry("new", true);
        assertThat(updated.getVersion()).isEqualTo("1"); // 版本缺省保持不变
    }
}
