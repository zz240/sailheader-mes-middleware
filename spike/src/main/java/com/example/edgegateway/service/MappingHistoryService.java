package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.config.ActionConfig;
import com.example.edgegateway.config.FieldMappingRule;
import com.example.edgegateway.config.MqConsumeConfig;
import com.example.edgegateway.config.ReadSqlConfig;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.MappingHistoryMapper;
import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.mapping.suggest.HistoryMapping;
import com.example.edgegateway.mapping.suggest.TransformKind;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.MappingHistory;
import com.example.edgegateway.model.SyncDirection;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 映射历史服务：保存 FIELD_MAP 动作时把确认规则 upsert 进历史表（资产沉淀），
 * 按 站点 + 方向 + 源结构指纹 供映射建议器查询。
 *
 * <p>方向由动作链推断：首动作为 MQ_CONSUME 为南向，否则北向。
 * 源结构指纹：南向取 MQ_CONSUME 队列名（mq:queue），北向从 READ_SQL 提取表名（db:table）。</p>
 */
@Service
public class MappingHistoryService {

    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?i)\\bfrom\\s+[`\"\\[]?([a-zA-Z0-9_$.`\"\\[\\]]+)");

    private final MappingHistoryMapper historyMapper;
    private final JobMapper jobMapper;
    private final ActionMapper actionMapper;

    public MappingHistoryService(
            MappingHistoryMapper historyMapper,
            JobMapper jobMapper,
            ActionMapper actionMapper,
            DataSourceMapper dataSourceMapper) {
        this.historyMapper = historyMapper;
        this.jobMapper = jobMapper;
        this.actionMapper = actionMapper;
    }

    /** 保存字段映射动作后调用：把确认的映射规则记录为历史（同键 upsert，最近确认优先）。 */
    public void recordFromAction(Long jobId, List<FieldMappingRule> rules) {
        Job job = jobMapper.selectById(jobId);
        if (job == null || rules == null || rules.isEmpty()) {
            return;
        }
        List<Action> actions = listActions(jobId);
        if (actions.stream().noneMatch(a -> a.getType() == ActionKind.READ_SQL
                || a.getType() == ActionKind.MQ_CONSUME)) {
            return; // 动作链还没有读入口，方向/源结构无法推断，历史记录跳过
        }
        SyncDirection direction = inferDirection(actions);
        String sourceKey = inferSourceKey(actions, direction);
        for (FieldMappingRule rule : rules) {
            upsert(job.getSiteId(), direction, sourceKey, rule);
        }
    }

    /** 按站点 + 方向加载历史，转成建议器的值对象。 */
    public List<HistoryMapping> listByScope(Long siteId, SyncDirection direction) {
        List<MappingHistory> rows = historyMapper.selectList(new LambdaQueryWrapper<MappingHistory>()
                .eq(MappingHistory::getSiteId, siteId)
                .eq(MappingHistory::getDirection, direction));
        List<HistoryMapping> result = new ArrayList<>();
        for (MappingHistory row : rows) {
            result.add(new HistoryMapping(
                    row.getSourceKey(),
                    row.getSourcePath(),
                    row.getTargetName(),
                    parseKind(row.getTransform()),
                    row.getDatePattern(),
                    row.getConstant(),
                    row.getEnumMap(),
                    row.getHitCount() == null ? 0 : row.getHitCount(),
                    row.getUpdatedAt() == null ? 0 : java.sql.Timestamp.valueOf(row.getUpdatedAt()).getTime()));
        }
        return result;
    }

    /** 方向推断：首动作为 MQ_CONSUME 为南向，否则北向（与 RouteGenerator 一致）。 */
    public SyncDirection inferDirection(List<Action> actions) {
        List<Action> sorted = actions.stream()
                .sorted(Comparator.comparing(Action::getActionIndex))
                .toList();
        if (sorted.isEmpty()) {
            return SyncDirection.NORTH;
        }
        return sorted.get(0).getType() == ActionKind.MQ_CONSUME
                ? SyncDirection.SOUTH
                : SyncDirection.NORTH;
    }

    /** 源结构指纹：南向 = mq:队列名；北向 = db:读 SQL 的表名（提取失败回退 db:数据源ID）。 */
    public String inferSourceKey(List<Action> actions, SyncDirection direction) {
        List<Action> sorted = actions.stream()
                .sorted(Comparator.comparing(Action::getActionIndex))
                .toList();
        if (direction == SyncDirection.SOUTH) {
            MqConsumeConfig consume = (MqConsumeConfig) ActionConfig.from(
                    ActionKind.MQ_CONSUME, addition(sorted.get(0)));
            String queue = consume.queue();
            return "mq:" + (queue == null || queue.isBlank() ? "unknown" : queue);
        }
        ReadSqlConfig read = (ReadSqlConfig) ActionConfig.from(
                ActionKind.READ_SQL, addition(firstRead(sorted)));
        String table = extractTable(read.sql());
        return table != null ? "db:" + table : "db:ds-" + read.dataSourceId();
    }

    private void upsert(Long siteId, SyncDirection direction, String sourceKey, FieldMappingRule rule) {
        MappingHistory existing = historyMapper.selectOne(new LambdaQueryWrapper<MappingHistory>()
                .eq(MappingHistory::getSiteId, siteId)
                .eq(MappingHistory::getDirection, direction)
                .eq(MappingHistory::getSourceKey, sourceKey)
                .eq(MappingHistory::getSourcePath, rule.source())
                .eq(MappingHistory::getTargetName, rule.target()));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            MappingHistory row = new MappingHistory();
            row.setSiteId(siteId);
            row.setDirection(direction);
            row.setSourceKey(sourceKey);
            row.setSourcePath(rule.source());
            row.setTargetName(rule.target());
            applyTransform(row, rule);
            row.setHitCount(0);
            row.setUpdatedAt(now);
            historyMapper.insert(row);
        } else {
            applyTransform(existing, rule);
            existing.setHitCount(existing.getHitCount() == null ? 1 : existing.getHitCount() + 1);
            existing.setUpdatedAt(now);
            historyMapper.updateById(existing);
        }
    }

    private void applyTransform(MappingHistory row, FieldMappingRule rule) {
        row.setTransform(rule.transform().name());
        row.setDatePattern(rule.datePattern());
        row.setConstant(rule.constant());
        row.setEnumMap(rule.enumMap());
    }

    private List<Action> listActions(Long jobId) {
        return actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .orderByAsc(Action::getActionIndex));
    }

    private Action firstRead(List<Action> actions) {
        return actions.stream()
                .filter(a -> a.getType() == ActionKind.READ_SQL)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("任务没有 READ_SQL 动作"));
    }

    private Map<String, Object> addition(Action action) {
        return action.getAddition() == null ? Map.of() : action.getAddition();
    }

    public static String extractTable(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = FROM_TABLE.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1).replaceAll("[`\"\\[\\]]", "");
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private TransformKind parseKind(String transform) {
        try {
            return switch (TransformType.valueOf(transform)) {
                case STRING -> TransformKind.STRING;
                case INT -> TransformKind.INT;
                case DATE -> TransformKind.DATE;
                case ENUM -> TransformKind.ENUM;
                case CONSTANT -> TransformKind.CONSTANT;
            };
        } catch (IllegalArgumentException e) {
            return TransformKind.STRING;
        }
    }
}
