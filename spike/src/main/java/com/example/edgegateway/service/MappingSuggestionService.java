package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapping.suggest.HistoryMapping;
import com.example.edgegateway.mapping.suggest.MappingSuggestion;
import com.example.edgegateway.mapping.suggest.MappingSuggester;
import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.mapping.suggest.TargetField;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.SyncDirection;
import com.example.edgegateway.web.dto.SuggestionRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 映射建议服务：按任务所在站点 + 方向加载映射历史，组装源/目标结构，
 * 调用规则引擎返回建议（历史复用 → 精确 → 相似度）。
 */
@Service
public class MappingSuggestionService {

    private final JobMapper jobMapper;
    private final ActionMapper actionMapper;
    private final MappingHistoryService historyService;
    private final MappingSuggesterRouter router;

    public MappingSuggestionService(
            JobMapper jobMapper,
            ActionMapper actionMapper,
            MappingHistoryService historyService,
            MappingSuggesterRouter router) {
        this.jobMapper = jobMapper;
        this.actionMapper = actionMapper;
        this.historyService = historyService;
        this.router = router;
    }

    public List<MappingSuggestion> suggest(SuggestionRequest request) {
        Job job = jobMapper.selectById(request.jobId());
        if (job == null) {
            throw new NoSuchElementException("任务不存在: " + request.jobId());
        }
        List<Action> actions = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, request.jobId())
                .orderByAsc(Action::getActionIndex));
        SyncDirection direction = job.getDirection() != null
                ? job.getDirection()
                : historyService.inferDirection(actions);
        List<HistoryMapping> history = historyService.listByScope(job.getSiteId(), direction);

        SourceStructure source = toSource(request.source());
        List<TargetField> targets = new ArrayList<>();
        for (SuggestionRequest.Target t : request.targets()) {
            targets.add(new TargetField(t.name(), t.required() == null || t.required()));
        }
        return router.forSite(job.getSiteId()).suggest(source, targets, history);
    }

    private SourceStructure toSource(SuggestionRequest.Source source) {
        List<SourceField> fields = new ArrayList<>();
        if (source != null) {
            for (SuggestionRequest.Field f : source.fields()) {
                fields.add(new SourceField(
                        f.name(),
                        f.type() == null ? "STRING" : f.type(),
                        f.nullable() == null || f.nullable(),
                        f.samples() == null ? List.of() : f.samples()));
            }
        }
        return new SourceStructure(source == null || source.sourceKey() == null ? "" : source.sourceKey(), fields);
    }
}
