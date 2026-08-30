package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceManagerTest {

    private DataSourceMapper mapper;
    private CamelContext camelContext;
    private Registry registry;
    private RecordingManager manager;

    @BeforeEach
    void setUp() {
        mapper = mock(DataSourceMapper.class);
        camelContext = mock(CamelContext.class);
        registry = mock(Registry.class);
        when(camelContext.getRegistry()).thenReturn(registry);
        manager = new RecordingManager(mapper, camelContext);
    }

    @Test
    void getBuildsAndBindsMysqlPoolWithConnectionParams() {
        DataSource ds = mysql(1L);
        when(mapper.selectById(1L)).thenReturn(ds);

        var pool = manager.get(1L);

        assertThat(pool).isNotNull();
        assertThat(manager.created()).hasSize(1);
        HikariConfig config = manager.configs().get(0);
        assertThat(config.getJdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/cust");
        assertThat(config.getUsername()).isEqualTo("cust_user");
        assertThat(config.getPassword()).isEqualTo("cust_pass");
        assertThat(config.getPoolName()).isEqualTo("edge-ds-1");
        // 懒建连：启动/单测不真连库
        assertThat(config.getInitializationFailTimeout()).isEqualTo(-1L);
        verify(registry).bind("edge-ds-1", pool);
    }

    @Test
    void getReturnsSameCachedInstanceWithoutRebuilding() {
        when(mapper.selectById(1L)).thenReturn(mysql(1L));

        var first = manager.get(1L);
        var second = manager.get(1L);

        assertThat(second).isSameAs(first);
        assertThat(manager.created()).hasSize(1);
    }

    @Test
    void getReturnsNullForNonMysqlWithoutBinding() {
        DataSource http = new DataSource();
        http.setId(2L);
        http.setType(DataSourceType.HTTP);
        when(mapper.selectById(2L)).thenReturn(http);

        assertThat(manager.get(2L)).isNull();
        assertThat(manager.created()).isEmpty();
        verify(registry, never()).bind(any(String.class), any());
    }

    @Test
    void getReturnsNullForMissingId() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThat(manager.get(9L)).isNull();
        verify(registry, never()).bind(any(String.class), any());
    }

    @Test
    void invalidateClosesAndUnbindsThenGetRebuilds() {
        when(mapper.selectById(1L)).thenReturn(mysql(1L));

        var first = manager.get(1L);
        manager.invalidate(1L);
        var second = manager.get(1L);

        verify(registry).unbind("edge-ds-1");
        assertThat(manager.created().get(0)).isNotNull();
        verify(manager.created().get(0)).close();
        assertThat(second).isNotNull().isNotSameAs(first);
        assertThat(manager.created()).hasSize(2);
    }

    @Test
    void invalidateUnknownIdIsHarmless() {
        manager.invalidate(999L);

        verify(registry).unbind("edge-ds-999");
    }

    @Test
    void initPoolsRegistersAllMysqlDataSources() {
        DataSource a = mysql(1L);
        DataSource b = mysql(2L);
        when(mapper.selectList(any())).thenReturn(List.of(a, b));

        manager.initPools();

        assertThat(manager.created()).hasSize(2);
        verify(registry).bind("edge-ds-1", manager.created().get(0));
        verify(registry).bind("edge-ds-2", manager.created().get(1));
    }

    @Test
    void initPoolsSkipsDataSourceWithInvalidConfig() {
        DataSource bad = mysql(1L);
        bad.setAddition(Map.of()); // 缺 username/password，validate 抛错
        DataSource good = mysql(2L);
        when(mapper.selectList(any())).thenReturn(List.of(bad, good));

        manager.initPools();

        // 仅好数据源建池，坏数据源不拖垮启动
        assertThat(manager.configs()).hasSize(1);
        assertThat(manager.configs().get(0).getPoolName()).isEqualTo("edge-ds-2");
        verify(registry).bind("edge-ds-2", manager.created().get(0));
        verify(registry, never()).bind(eq("edge-ds-1"), any());
    }

    private DataSource mysql(Long id) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddress("jdbc:mysql://localhost:3306/cust");
        ds.setAddition(Map.of("username", "cust_user", "password", "cust_pass"));
        return ds;
    }

    /** 测试用 seam：记录传给 buildPool 的配置，并返回可校验关闭的 mock 池。 */
    private static class RecordingManager extends DataSourceManager {
        private final List<HikariConfig> configs = new ArrayList<>();
        private final List<HikariDataSource> created = new ArrayList<>();

        RecordingManager(DataSourceMapper mapper, CamelContext camelContext) {
            super(mapper, camelContext);
        }

        @Override
        protected javax.sql.DataSource buildPool(HikariConfig config) {
            configs.add(config);
            HikariDataSource pool = mock(HikariDataSource.class);
            created.add(pool);
            return pool;
        }

        List<HikariConfig> configs() {
            return configs;
        }

        List<HikariDataSource> created() {
            return created;
        }
    }
}
