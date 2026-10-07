package com.example.edgegateway.service;

import com.example.edgegateway.config.datasource.DataSourceConfig;
import com.example.edgegateway.config.datasource.JdbcDataSourceConfig;
import com.example.edgegateway.config.action.ActionConfig;
import com.example.edgegateway.config.action.ReadSqlConfig;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.service.sql.SqlDialects;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * 结构探测：连库读表结构 + 前 N 行原始样例（含重复，供转换推断），
 * 失败且提供样例时回退样例模式。结果按数据源缓存 5 分钟，refresh 强制刷新。
 * 只发只读 SQL（SELECT + LIMIT），不做任何 DDL/DML。
 */
@Service
public class StructureProbeService {

    private static final int SAMPLE_LIMIT = 20;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Pattern SAFE_TABLE = Pattern.compile("[A-Za-z0-9_]+");

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ActionMapper actionMapper;
    private final DataSourceMapper dataSourceMapper;
    /** 真正连库那一步；单测里换成假的，好观察缓存行为（连了几次库）。 */
    private final BiFunction<DataSource, String, SourceStructure> dbProbe;

    /** 连库那一步用真的；这是 Spring 用的那个构造器（多一个构造器时要指给它）。 */
    @Autowired
    public StructureProbeService(ActionMapper actionMapper, DataSourceMapper dataSourceMapper) {
        this(actionMapper, dataSourceMapper, null);
    }

    /**
     * @param dbProbe 连库探测的实现；传 null 用真的（{@link #probeDb}）。
     *                有这个口子是为了让缓存行为可测——缓存错不错，只有「连了几次库」能说明。
     */
    StructureProbeService(ActionMapper actionMapper, DataSourceMapper dataSourceMapper,
                          BiFunction<DataSource, String, SourceStructure> dbProbe) {
        this.actionMapper = actionMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.dbProbe = dbProbe == null ? this::probeDb : dbProbe;
    }

    public SourceStructure probeFromDb(DataSource dataSource, String table, boolean refresh) {
        if (dataSource == null || !SqlDialects.isJdbc(dataSource.getType())) {
            throw new IllegalArgumentException("该数据源类型不支持结构探测: "
                    + (dataSource == null ? null : dataSource.getType()));
        }
        if (table == null || !SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("表名不合法，仅允许字母/数字/下划线: " + table);
        }
        // 缓存键必须带表名：一个数据源上可以有很多张表，只按数据源缓存会把上一次那张表的字段返回给这一次。
        CacheKey key = new CacheKey(dataSource.getId(), table);
        CacheEntry entry = refresh ? null : cache.get(key);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.structure();
        }
        SourceStructure structure = dbProbe.apply(dataSource, table);
        cache.put(key, new CacheEntry(structure, Instant.now().plus(CACHE_TTL)));
        return structure;
    }

    /** 样例兜底：递归展开叶子字段，类型粗推断，供无连库权限时使用。 */
    public SourceStructure probeSample(Object sample) {
        List<SourceField> fields = new ArrayList<>();
        walk("", sample, fields);
        return new SourceStructure("sample", fields);
    }

    /** 从引用该数据源的 READ_SQL 动作 SQL 推断表名（用于探测时预填），无则返回 null。 */
    public String suggestTable(Long dataSourceId) {
        List<Action> actions = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getType, ActionKind.READ_SQL)
                .orderByAsc(Action::getActionIndex));
        for (Action action : actions) {
            ReadSqlConfig config = (ReadSqlConfig) ActionConfig.from(
                    ActionKind.READ_SQL, action.getAddition() == null ? Map.of() : action.getAddition());
            if (config.dataSourceId() != null && config.dataSourceId().equals(dataSourceId)
                    && config.sql() != null) {
                String table = MappingHistoryService.extractTable(config.sql());
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }

    private SourceStructure probeDb(DataSource dataSource, String table) {
        JdbcDataSourceConfig config = (JdbcDataSourceConfig)
                DataSourceConfig.from(dataSource.getType(), dataSource.getAddition());
        String sql = SqlDialects.of(dataSource.getType()).probeTable(table, SAMPLE_LIMIT);
        try (Connection conn = DriverManager.getConnection(dataSource.getAddress(), config.username(), config.password());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            List<List<Object>> samples = new ArrayList<>();
            for (int i = 0; i < cols; i++) {
                samples.add(new ArrayList<>());
            }
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    samples.get(i - 1).add(rs.getObject(i));
                }
            }
            List<SourceField> fields = new ArrayList<>();
            for (int i = 1; i <= cols; i++) {
                fields.add(new SourceField(
                        meta.getColumnLabel(i),
                        meta.getColumnTypeName(i),
                        meta.isNullable(i) != ResultSetMetaData.columnNoNulls,
                        samples.get(i - 1)));
            }
            return new SourceStructure("db:" + table, fields);
        } catch (Exception e) {
            throw new IllegalStateException("结构探测失败: " + e.getMessage(), e);
        }
    }

    private void walk(String prefix, Object value, List<SourceField> fields) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String path = prefix.isEmpty() ? entry.getKey().toString() : prefix + "." + entry.getKey();
                walk(path, entry.getValue(), fields);
            }
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty()) {
                walk(prefix + "[0]", list.get(0), fields);
            }
        } else {
            fields.add(new SourceField(
                    prefix,
                    value == null ? "NULL" : simpleType(value),
                    true,
                    value == null ? List.of() : List.of(value)));
        }
    }

    private String simpleType(Object value) {
        if (value instanceof Number) {
            return "NUMBER";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        return "STRING";
    }

    private record CacheEntry(SourceStructure structure, Instant expiresAt) {
    }

    /** 探测结果的作用域：一个数据源上的一张表。 */
    private record CacheKey(Long dataSourceId, String table) {
    }
}
