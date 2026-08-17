package com.example.edgegateway.service;

import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.MysqlDataSourceConfig;
import com.example.edgegateway.config.RemoteAccessConfig;
import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.mapping.suggest.NameNormalizer;
import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionType;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.SyncDirection;
import com.example.edgegateway.web.dto.ValidationResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 任务级配置期校验：数据源连通性 + SQL 静态/只读真校验 + 北向映射字段存在性（探测结果对照）。
 * errors 阻断启用，warnings 仅提示（对应「引擎侧结构校验」决策）。
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
    private final MappingHistoryService historyService;
    private final ReadOnlySqlValidator sqlValidator;

    public JobValidationService(
            JobService jobService,
            ActionService actionService,
            DataSourceMapper dataSourceMapper,
            ConnectionTester connectionTester,
            StructureProbeService probeService,
            MappingHistoryService historyService,
            ReadOnlySqlValidator sqlValidator) {
        this.jobService = jobService;
        this.actionService = actionService;
        this.dataSourceMapper = dataSourceMapper;
        this.connectionTester = connectionTester;
        this.probeService = probeService;
        this.historyService = historyService;
        this.sqlValidator = sqlValidator;
    }

    public ValidationResult validate(Long jobId) {
        List<ValidationResult.Issue> errors = new ArrayList<>();
        List<ValidationResult.Issue> warnings = new ArrayList<>();
        Job job = jobService.get(jobId);
        List<Action> actions = actionService.list(jobId);
        if (actions.isEmpty()) {
            errors.add(new ValidationResult.Issue("job", "actions", "任务没有动作，无法启用"));
            return new ValidationResult(false, errors, warnings);
        }
        SyncDirection direction = historyService.inferDirection(actions);

        for (Action action : actions) {
            if (action.getType() != ActionType.REMOTE_ACCESS) {
                continue;
            }
            validateRemoteAction(action, errors);
        }
        if (direction == SyncDirection.NORTH) {
            checkNorthMappingFields(actions, warnings);
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    private void validateRemoteAction(Action action, List<ValidationResult.Issue> errors) {
        RemoteAccessConfig config = RemoteAccessConfig.from(addition(action));
        DataSource ds = config.dataSourceId() == null ? null : dataSourceMapper.selectById(config.dataSourceId());
        if (ds == null) {
            errors.add(new ValidationResult.Issue("action", action.getName(), "引用的数据源不存在"));
            return;
        }
        ConnectionTester.TestResult test = connectionTester.test(ds);
        if (!test.ok()) {
            errors.add(new ValidationResult.Issue("data-source", ds.getName(), "连接失败: " + test.message()));
            return;
        }
        if (ds.getType() == DataSourceType.MYSQL && config.sql() != null && !config.sql().isBlank()) {
            validateSql(ds, action, config, errors);
        }
    }

    private void validateSql(
            DataSource ds,
            Action action,
            RemoteAccessConfig config,
            List<ValidationResult.Issue> errors) {
        String sql = config.sql();
        if (sql.contains(";")) {
            errors.add(new ValidationResult.Issue("sql", action.getName(), "SQL 不允许多语句（分号）"));
            return;
        }
        if (WRITE_KEYWORDS.matcher(sql).find()) {
            errors.add(new ValidationResult.Issue("sql", action.getName(), "SQL 不允许写语句/危险关键字"));
            return;
        }
        try {
            MysqlDataSourceConfig db = (MysqlDataSourceConfig) DataSourceConfig.from(ds.getType(), ds.getAddition());
            sqlValidator.validate(db, sql);
        } catch (Exception e) {
            errors.add(new ValidationResult.Issue("sql", action.getName(), e.getMessage()));
        }
    }

    /** 北向映射字段存在性：探测读表结构，映射源路径不在探测结果中给警告（常量规则跳过）。 */
    private void checkNorthMappingFields(List<Action> actions, List<ValidationResult.Issue> warnings) {
        Action read = actions.stream()
                .filter(a -> a.getType() == ActionType.REMOTE_ACCESS)
                .filter(a -> {
                    RemoteAccessConfig c = RemoteAccessConfig.from(addition(a));
                    return c.sql() != null && !c.sql().isBlank();
                })
                .findFirst()
                .orElse(null);
        if (read == null) {
            return;
        }
        RemoteAccessConfig config = RemoteAccessConfig.from(addition(read));
        DataSource ds = dataSourceMapper.selectById(config.dataSourceId());
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
            if (action.getType() != ActionType.FIELD_MAPPING) {
                continue;
            }
            FieldMappingConfig mapping = FieldMappingConfig.from(addition(action));
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

    private Map<String, Object> addition(Action action) {
        return action.getAddition() == null ? Map.of() : action.getAddition();
    }
}
