package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialMigrationServiceTest {

    @Test
    void reSavesAllDataSourcesWithAddition() {
        DataSourceMapper mapper = mock(DataSourceMapper.class);
        DataSource ds1 = new DataSource();
        ds1.setId(1L);
        ds1.setName("a");
        ds1.setAddition(Map.of("password", "p1"));
        DataSource ds2 = new DataSource();
        ds2.setId(2L);
        ds2.setName("b");
        ds2.setAddition(Map.of());
        when(mapper.selectList(any())).thenReturn(List.of(ds1, ds2));

        List<String> migrated = new CredentialMigrationService(mapper).migrateAll();

        assertThat(migrated).containsExactly("a(#1)");
        verify(mapper, times(1)).updateById(ds1);
        verify(mapper, times(0)).updateById(ds2);
    }
}
