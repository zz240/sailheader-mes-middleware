package com.example.edgegateway.service;

import com.example.edgegateway.config.ActionConfig;
import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.MqConsumeConfig;
import com.example.edgegateway.config.JdbcDataSourceConfig;
import com.example.edgegateway.service.sql.SqlDialects;
import com.example.edgegateway.config.ReadSqlConfig;
import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.mapping.suggest.NameNormalizer;
import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.ValidationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 任务级配置期校验：数据源连通性 + READ_SQL 静态/只读真校验 + 北向映射字段存在性（探测结果对照）
 * + 动作链 sourceStep 引用一致性（FIELD_MAP）+ MQ 消费绑定提示（fanout 配 routingKey）。
 * errors 阻断启用，warnings 仅提示。
 */
@Service
public class JobValidationService {

    private static final Pattern WRITE_KEYWORDS = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|replace|load_file|"
                    + "into\\s+outfile)\\b");

    private final JobService jobService;
    private final ActionService actionService;
    private final DataSourceMapper dataSourceMapper;
    private final ConnectionTester connectionTester;
    private final StructureProbeService probeService;
    private final ReadOnlySqlValidator sqlValidator;

    public JobValidationService(
            JobService jobService,
            ActionService actionService,
            DataSourceMapper dataSourceMapper,
            ConnectionTester connectionTester,
            StructureProbeService probeService,
            ReadOnlySqlValidator sqlValidator) {
        this.jobService = jobService;
        this.actionService = actionService;
        this.dataSourceMapper = dataSourceMapper;
        this.connectionTester = connectionTester;
        this.probeService = probeService;
        this.sqlValidator = sqlValidator;
    }

    public ValidationResult validate(Long jobId) {
        List<ValidationResult.Issue> errors = new ArrayList<>();
        List<ValidationResult.Issue> warnings = new ArrayList<>();
        jobService.get(jobId);
        List<Action> actions = actionService.list(jobId);
        if (actions.isEmpty()) {
            errors.add(new ValidationResult.Issue("job", "actions", "任务没有动作，无法启用"));
            return new ValidationResult(false, errors, warnings);
        }
        for (Action action : actions) {
            if (!hasDataSource(action.getType())) {
                continue;
            }
            validateRemoteAction(action, errors);
        }
        checkChainReferences(actions, errors);
        checkNorthMappingFields(actions, warnings);
        checkMqConsumeBindings(actions, warnings);
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * 这个动作种类是否引用数据源。**不含 FIELD_MAP / SPLIT / AGGREGATE / FANOUT**：字段映射、拆分、
     * 合并与扇回都是在链上干活的动作，不连任何库/队列/HTTP——把它们也算进来的话，一个合法的拆分/合并动作
     * 会被报成「缺少 dataSourceId」（真机第一次跑这条链就是这么被拦下的）。合并**不再**自带出口
     * （ADR-0017 决策 6 的修订）：发去哪儿由后面的 {@code HTTP_CALL} / {@code MQ_PUBLISH} 决定。
     */
    private boolean hasDataSource(ActionKind kind) {
        return kind != ActionKind.FIELD_MAP && kind != ActionKind.SPLIT
                && kind != ActionKind.AGGREGATE && kind != ActionKind.FANOUT;
    }

    private void validateRemoteAction(Action action, List<ValidationResult.Issue> errors) {
        ActionConfig config = ActionConfig.from(action.getType(), addition(action));
        Object rawId = config.toMap().get("dataSourceId");
        if (rawId == null) {
            errors.add(new ValidationResult.Issue("action", action.getName(), "缺少 dataSourceId"));
            return;
        }
        DataSource ds = dataSourceMapper.selectById(Long.valueOf(rawId.toString()));
        if (ds == null) {
            errors.add(new ValidationResult.Issue("action", action.getName(), "引用的数据源不存在"));
            return;
        }
        ConnectionTester.TestResult test = connectionTester.test(ds);
        if (!test.ok()) {
            errors.add(new ValidationResult.Issue("data-source", ds.getName(), "连接失败: " + test.message()));
            return;
        }
        if (action.getType() == ActionKind.READ_SQL && SqlDialects.isJdbc(ds.getType())) {
            ReadSqlConfig read = (ReadSqlConfig) config;
            validateSql(ds, action, read, errors);
        }
    }

    private void validateSql(
            DataSource ds,
            Action action,
            ReadSqlConfig read,
            List<ValidationResult.Issue> errors) {
        try {
            read.validate(); // 定位 batchSize/watermarkType/水位列 等配置错误
        } catch (IllegalArgumentException e) {
            errors.add(new ValidationResult.Issue("read", action.getName(), e.getMessage()));
            return;
        }
        String sql = read.sql();
        if (sql == null || sql.isBlank()) {
            errors.add(new ValidationResult.Issue("sql", action.getName(), "读 SQL 动作需要 sql"));
            return;
        }
        if (sql.contains(";")) {
            errors.add(new ValidationResult.Issue("sql", action.getName(), "SQL 不允许多语句（分号）"));
            return;
        }
        if (WRITE_KEYWORDS.matcher(sql).find()) {
            errors.add(new ValidationResult.Issue("sql", action.getName(), "SQL 不允许写语句/危险关键字"));
            return;
        }
        try {
            JdbcDataSourceConfig db = (JdbcDataSourceConfig) DataSourceConfig.from(ds.getType(), ds.getAddition());
            sqlValidator.validate(ds.getAddress(), db, sql);
        } catch (Exception e) {
            errors.add(new ValidationResult.Issue("sql", action.getName(), e.getMessage()));
        }
    }

    /** 链一致性（#89 T3a）：FIELD_MAP.sourceStep 必须指向链中存在的、序号更早的动作，悬空/错位阻断启用。 */
    private void checkChainReferences(List<Action> actions, List<ValidationResult.Issue> errors) {
        for (String problem : ChainConsistencyValidator.check(actions)) {
            errors.add(new ValidationResult.Issue("mapping", "sourceStep", problem));
        }
    }

    /** 北向映射字段存在性：探测 READ_SQL 表结构，FIELD_MAP 源路径不在探测结果中给警告。 */
    private void checkNorthMappingFields(List<Action> actions, List<ValidationResult.Issue> warnings) {
        Action read = actions.stream()
                .filter(a -> a.getType() == ActionKind.READ_SQL)
                .findFirst()
                .orElse(null);
        if (read == null) {
            return;
        }
        ReadSqlConfig config = (ReadSqlConfig) ActionConfig.from(ActionKind.READ_SQL, addition(read));
        DataSource ds = config.dataSourceId() == null ? null
                : dataSourceMapper.selectById(config.dataSourceId());
        String table = MappingHistoryService.extractTable(config.sql());
        if (ds == null || table == null) {
            return;
        }
        SourceStructure structure;
        try {
            structure = probeService.probeFromDb(ds, table, false);
        } catch (Exception e) {
            warnings.add(new ValidationResult.Issue(
                    "probe", ds.getName(), "无法探测表结构（" + table + "），跳过字段存在性校验: " + e.getMessage()));
            return;
        }
        List<String> probed = structure.fields().stream()
                .map(f -> normalizePath(f.name()))
                .toList();
        for (Action action : actions) {
            if (action.getType() != ActionKind.FIELD_MAP) {
                continue;
            }
            FieldMappingConfig mapping = (FieldMappingConfig)
                    ActionConfig.from(ActionKind.FIELD_MAP, addition(action));
            for (var rule : mapping.rules()) {
                if (rule.transform() == TransformType.CONSTANT) {
                    continue;
                }
                String path = normalizePath(rule.source());
                if (probed.stream().noneMatch(path::equals)) {
                    warnings.add(new ValidationResult.Issue(
                            "mapping", rule.target(), "源字段 " + rule.source() + " 不在探测结果中（可能为业务自定义路径）"));
                }
            }
        }
    }

    private String normalizePath(String path) {
        return NameNormalizer.normalize(path).compact().replace("[0]", "");
    }

    /** MQ 消费绑定提示（#81）：fanout exchange 由 broker 忽略 binding key（routingKey），配置了仅提示不阻断。 */
    private void checkMqConsumeBindings(List<Action> actions, List<ValidationResult.Issue> warnings) {
        for (Action action : actions) {
            if (action.getType() != ActionKind.MQ_CONSUME) {
                continue;
            }
            MqConsumeConfig consume = (MqConsumeConfig) ActionConfig.from(
                    ActionKind.MQ_CONSUME, addition(action));
            if ("fanout".equals(consume.effectiveExchangeType())
                    && consume.routingKey() != null && !consume.routingKey().isBlank()) {
                warnings.add(new ValidationResult.Issue(
                        "action", action.getName(), "fanout exchange 忽略 routingKey（binding key），建议留空"));
            }
        }
    }

    private Map<String, Object> addition(Action action) {
        return action.getAddition() == null ? Map.of() : action.getAddition();
    }
}
