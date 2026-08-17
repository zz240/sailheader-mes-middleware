package com.example.edgegateway.mapping.suggest;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认规则引擎实现（对应 #13 评分器链：历史 → 归一化精确 → Jaro-Winkler 相似）。
 *
 * <p>每个必填目标字段输出 top N（默认 3）候选，按得分降序；低于阈值不强猜。
 * 纯函数、无 IO、无 Spring 依赖，可直接用于单测与评测集。</p>
 *
 * <p>映射历史规则：键 = (源结构指纹, 源字段, 目标字段)，同键多条取最近确认；
 * 历史命中时带出上次确认的转换类型与参数（不重新按样例推断）；
 * 多个 1.0 候选按 得分 → 复用次数 → 最近确认 排序。</p>
 */
@Component
public final class RuleMappingSuggester implements MappingSuggester {

    private static final int TOP_N = 3;

    private final double threshold;
    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();

    public RuleMappingSuggester(double threshold) {
        this.threshold = threshold;
    }

    public RuleMappingSuggester() {
        this(0.85);
    }

    @Override
    public List<MappingSuggestion> suggest(
            SourceStructure source,
            List<TargetField> targetFields,
            List<HistoryMapping> history
    ) {
        Map<HistoryKey, HistoryMapping> historyByKey = indexHistory(history);
        Map<String, NameNormalizer.Normalized> normCache = new HashMap<>();

        List<MappingSuggestion> all = new ArrayList<>();
        for (TargetField target : targetFields) {
            if (!target.required()) {
                continue;
            }
            NameNormalizer.Normalized t = norm(normCache, target.name());
            List<Candidate> candidates = new ArrayList<>();
            for (SourceField sourceField : source.fields()) {
                NameNormalizer.Normalized s = norm(normCache, sourceField.name());
                Candidate candidate = match(sourceField, source.key(), target, s, t, historyByKey);
                if (candidate.suggestion() != null) {
                    candidates.add(candidate);
                }
            }
            candidates.sort(candidateComparator());
            all.addAll(candidates.stream().limit(TOP_N).map(Candidate::suggestion).toList());
        }
        return all;
    }

    /** 历史命中（1.0）优先，其次归一化精确（1.0），最后 Jaro-Winkler 相似（>= 阈值）。 */
    private Candidate match(
            SourceField sourceField,
            String sourceKey,
            TargetField target,
            NameNormalizer.Normalized s,
            NameNormalizer.Normalized t,
            Map<HistoryKey, HistoryMapping> historyByKey
    ) {
        HistoryMapping history = historyByKey.get(new HistoryKey(sourceKey, s.compact(), t.compact()));
        if (history != null) {
            MappingSuggestion suggestion = new MappingSuggestion(
                    sourceField.name(),
                    target.name(),
                    1.0,
                    history.transform(),
                    history.datePattern(),
                    history.constant(),
                    history.enumMap(),
                    MatchSource.HISTORY
            );
            return new Candidate(suggestion, history.hitCount(), history.updatedAt());
        }
        TransformInferrer.Inference inference = TransformInferrer.infer(sourceField.samples());
        if (!s.tokens().isEmpty() && s.tokens().equals(t.tokens())) {
            return new Candidate(suggestion(sourceField, target, 1.0, MatchSource.EXACT, inference), 0, 0);
        }
        double score = jaroWinkler.apply(s.compact(), t.compact());
        if (score >= threshold) {
            return new Candidate(suggestion(sourceField, target, score, MatchSource.SIMILAR, inference), 0, 0);
        }
        return new Candidate(null, 0, 0);
    }

    private MappingSuggestion suggestion(
            SourceField sourceField,
            TargetField target,
            double score,
            MatchSource matchSource,
            TransformInferrer.Inference inference
    ) {
        return new MappingSuggestion(
                sourceField.name(),
                target.name(),
                score,
                inference.kind(),
                inference.datePattern(),
                inference.constant(),
                null,
                matchSource
        );
    }

    private static NameNormalizer.Normalized norm(Map<String, NameNormalizer.Normalized> cache, String name) {
        return cache.computeIfAbsent(name, NameNormalizer::normalize);
    }

    /** 历史索引：同键保留最近确认（updatedAt 大者优先，同级取 hitCount 大者）。 */
    private static Map<HistoryKey, HistoryMapping> indexHistory(List<HistoryMapping> history) {
        Map<HistoryKey, HistoryMapping> index = new HashMap<>();
        for (HistoryMapping h : history) {
            HistoryKey key = new HistoryKey(
                    h.sourceKey(),
                    NameNormalizer.normalize(h.sourcePath()).compact(),
                    NameNormalizer.normalize(h.targetName()).compact());
            HistoryMapping existing = index.get(key);
            if (existing == null
                    || h.updatedAt() > existing.updatedAt()
                    || (h.updatedAt() == existing.updatedAt() && h.hitCount() > existing.hitCount())) {
                index.put(key, h);
            }
        }
        return index;
    }

    private static Comparator<Candidate> candidateComparator() {
        return Comparator
                .comparingDouble((Candidate c) -> c.suggestion().score())
                .reversed()
                .thenComparing(Comparator.comparingInt(Candidate::hitCount).reversed())
                .thenComparing(Comparator.comparingLong(Candidate::updatedAt).reversed());
    }

    /** 候选 + 排序辅助信息（历史复用次数与最近确认时间）。 */
    private record Candidate(MappingSuggestion suggestion, int hitCount, long updatedAt) {
    }

    /** 历史键：源结构指纹 + 归一化后的源字段 + 目标字段。 */
    private record HistoryKey(String sourceKey, String sourceCompact, String targetCompact) {
    }
}
