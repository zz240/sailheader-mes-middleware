package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.web.dto.DataSourceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceServiceTest {

    private DataSourceMapper mapper;
    private DataSourceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DataSourceMapper.class);
        service = new DataSourceService(mapper);
    }

    private DataSourceRequest request(String name, DataSourceType type, Map<String, Object> addition) {
        return new DataSourceRequest(name, 1L, type, "http://localhost:8080/mock", addition);
    }

    @Test
    void createsValidDataSource() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(DataSource.class))).thenAnswer(inv -> {
            inv.getArgument(0, DataSource.class).setId(1L);
            return 1;
        });

        DataSource saved = service.create(request("erp", DataSourceType.HTTP, Map.of("method", "GET")));

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getName()).isEqualTo("erp");
        assertThat(saved.getType()).isEqualTo(DataSourceType.HTTP);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create(request("", DataSourceType.HTTP, Map.of("method", "GET"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRequiredAdditionField() {
        assertThatThrownBy(() -> service.create(request("mysql", DataSourceType.MYSQL, Map.of("username", "sa"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void rejectsDuplicateNameInSite() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("dup", DataSourceType.HTTP, Map.of("method", "GET"))))
                .isInstanceOf(BusinessException.class);
    }
}
