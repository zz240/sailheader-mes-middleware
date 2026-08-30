package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceAdditionMigrationTest {

    private final DataSourceMapper mapper = mock(DataSourceMapper.class);
    private final DataSourceAdditionMigration migration = new DataSourceAdditionMigration(mapper);

    @Test
    void stripsMqResourceKeys() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setType(DataSourceType.RABBITMQ);
        ds.setAddition(new LinkedHashMap<>(Map.of(
                "queue", "mes.to.edge", "exchange", "edge", "routingKey", "rk",
                "host", "localhost", "port", 5672)));
        when(mapper.selectList(any())).thenReturn(List.of(ds));

        migration.stripLegacyKeys();

        assertThat(ds.getAddition()).doesNotContainKey("queue");
        assertThat(ds.getAddition()).doesNotContainKey("exchange");
        assertThat(ds.getAddition()).doesNotContainKey("routingKey");
        assertThat(ds.getAddition()).containsEntry("host", "localhost");
        verify(mapper).updateById(ds);
    }

    @Test
    void stripsMysqlUrl() {
        DataSource ds = new DataSource();
        ds.setId(2L);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition(new LinkedHashMap<>(Map.of(
                "url", "jdbc:mysql://localhost:3306/x", "username", "u", "password", "p")));
        when(mapper.selectList(any())).thenReturn(List.of(ds));

        migration.stripLegacyKeys();

        assertThat(ds.getAddition()).doesNotContainKey("url");
        assertThat(ds.getAddition()).containsEntry("username", "u");
        assertThat(ds.getAddition()).containsEntry("password", "p");
        verify(mapper).updateById(ds);
    }

    @Test
    void skipsRowsWithoutLegacyKeys() {
        DataSource ds = new DataSource();
        ds.setId(3L);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition(new LinkedHashMap<>(Map.of("username", "u", "password", "p")));
        when(mapper.selectList(any())).thenReturn(List.of(ds));

        migration.stripLegacyKeys();

        verify(mapper, never()).updateById(any(DataSource.class));
    }
}
