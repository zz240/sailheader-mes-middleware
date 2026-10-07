package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.config.datasource.DataSourceConfig;
import com.example.edgegateway.config.datasource.JdbcDataSourceConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.service.sql.SqlDialect;
import com.example.edgegateway.service.sql.SqlDialects;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.camel.CamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源连接池管理（#65）：为每个关系库数据源（MySQL / SQL Server，ADR-0014）维护一个命名 HikariCP 连接池，
 * 并以 {@code edge-ds-<id>} 注册为 Camel registry bean，供路由按 id 引用。
 *
 * <p>HTTP/RabbitMQ 数据源无 JDBC 语义，不建池。连接池延迟建连（Hikari
 * {@code initializationFailTimeout} 设为负值），建池/启动不会阻塞在网络上。</p>
 *
 * <p>时序：本监听器必须在 {@link RouteRegistry#reloadEnabledJobs()} 之前执行，
 * 以便路由构建时数据源 bean 已就位（由 {@link Order} 控制先后）。</p>
 */
@Component
public class DataSourceManager {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceManager.class);
    private static final String BEAN_PREFIX = "edge-ds-";

    private final Map<Long, javax.sql.DataSource> pools = new ConcurrentHashMap<>();
    /** 池对应库的方言：建池时行就在手边，顺手记下，省得每次拼 SQL 再查一遍行（分批读是每批都要方言的）。 */
    private final Map<Long, SqlDialect> dialects = new ConcurrentHashMap<>();
    private final DataSourceMapper dataSourceMapper;
    private final CamelContext camelContext;

    public DataSourceManager(DataSourceMapper dataSourceMapper, CamelContext camelContext) {
        this.dataSourceMapper = dataSourceMapper;
        this.camelContext = camelContext;
    }

    /** 应用就绪后遍历所有关系库数据源建池并登记为 Camel bean。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void initPools() {
        List<DataSource> rows = dataSourceMapper.selectList(
                new LambdaQueryWrapper<DataSource>().in(DataSource::getType, SqlDialects.jdbcTypes()));
        for (DataSource ds : rows) {
            try {
                pools.computeIfAbsent(ds.getId(), key -> createPool(ds));
            } catch (RuntimeException e) {
                // 单个数据源配置有问题不应拖垮启动，记录后继续
                LOG.warn("启动建池失败，跳过数据源 id={}: {}", ds.getId(), e.getMessage());
            }
        }
    }

    /**
     * 按数据源 id 取连接池：命中返回；未命中或非关系库返回 {@code null}。
     * 幂等：已有池不重建；{@code invalidate} 之后再次调用会按需重建。
     */
    public javax.sql.DataSource get(Long id) {
        if (id == null) {
            return null;
        }
        javax.sql.DataSource cached = pools.get(id);
        if (cached != null) {
            return cached;
        }
        DataSource ds = dataSourceMapper.selectById(id);
        if (ds == null || !SqlDialects.isJdbc(ds.getType())) {
            return null;
        }
        return pools.computeIfAbsent(id, key -> createPool(ds));
    }

    /**
     * 取某个数据源对应库的方言；不是关系库、或查不到行时返回 {@code null}（调用方据此报错，不要猜一个默认方言）。
     * 与 {@link #get(Long)} 一样可懒建池：池建好时方言就记下了。
     */
    public SqlDialect dialect(Long id) {
        if (id == null) {
            return null;
        }
        SqlDialect known = dialects.get(id);
        if (known != null) {
            return known;
        }
        get(id);
        return dialects.get(id);
    }

    /** 关闭并移除数据源的连接池与 Camel bean（删除数据源/连接配置变更时调用）。 */
    public void invalidate(Long id) {
        if (id == null) {
            return;
        }
        javax.sql.DataSource pool = pools.remove(id);
        dialects.remove(id);
        if (pool instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("关闭连接池失败 id={}: {}", id, e.getMessage());
            }
        }
        camelContext.getRegistry().unbind(BEAN_PREFIX + id);
    }

    private javax.sql.DataSource createPool(DataSource ds) {
        JdbcDataSourceConfig config = (JdbcDataSourceConfig)
                DataSourceConfig.from(ds.getType(), ds.getAddition());
        config.validate();

        HikariConfig hikari = new HikariConfig();
        // JDBC URL 取自数据源 address（ADR-0007 连接级，addition 仅存 username/password）
        hikari.setJdbcUrl(ds.getAddress());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setPoolName(BEAN_PREFIX + ds.getId());
        // 负值 = 启动时不尝试初始连接，真正用到时才建连（懒建连），避免测试/启动被网络阻塞
        hikari.setInitializationFailTimeout(-1L);

        javax.sql.DataSource pool = buildPool(hikari);
        camelContext.getRegistry().bind(BEAN_PREFIX + ds.getId(), pool);
        dialects.put(ds.getId(), SqlDialects.of(ds.getType()));
        return pool;
    }

    /** 创建底层连接池；以 protected 暴露为 seam，便于测试替换或校验关闭。 */
    protected javax.sql.DataSource buildPool(HikariConfig config) {
        return new HikariDataSource(config);
    }
}
