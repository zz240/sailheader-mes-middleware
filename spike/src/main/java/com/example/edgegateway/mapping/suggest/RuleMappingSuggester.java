package com.example.edgegateway.mapping.suggest;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认规则引擎实现（对应 #13 评分器链：历史 → 归一化精确 → Jaro-Winkler 相似）。
 *
 * <p>每个必填目标字段输出 top N（默认 3）候选，按得分降序；低于阈值不强猜。
 * 纯函数、无 IO、无 Spring 依赖，可直接用于单测与评测集。</p>
 */
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
            List<SourceField> sourceFields,
            List<TargetField> targetFields,
            List<HistoryMapping> history
    ) {
        Set<String> historyKeys = new HashSet<>();
        for (HistoryMapping h : history) {
            historyKeys.add(key(h.sourcePath(), h.targetName()));
        }
        Map<String, NameNormalizer.Normalized> normCache = new HashMap<>();

        List<MappingSuggestion> all = new ArrayList<>();
        for (TargetField target : targetFields) {
            if (!target.required()) {
                continue;
            }
            NameNormalizer.Normalized t = norm(normCache, target.name());
            List<MappingSuggestion> candidates = new ArrayList<>();
            for (SourceField source : sourceFields) {
                NameNormalizer.Normalized s = norm(normCache, source.name());
                MappingSuggestion suggestion = match(source, target, s, t, historyKeys);
                if (suggestion != null) {
                    candidates.add(suggestion);
                }
            }
            candidates.sort(Comparator.comparingDouble(MappingSuggestion::score).reversed());
            all.addAll(candidates.stream().limit(TOP_N).toList());
        }
        return all;
    }

    /** 历史命中（1.0）优先，其次归一化精确（1.0），最后 Jaro-Winkler 相似（>= 阈值）。 */
    private MappingSuggestion match(
            SourceField source,
            TargetField target,
            NameNormalizer.Normalized s,
            NameNormalizer.Normalized t,
            Set<String> historyKeys
    ) {
        TransformInferrer.Inference inference = TransformInferrer.infer(source.samples());
        if (historyKeys.contains(key(source.name(), target.name()))) {
            return suggestion(source, target, 1.0, MatchSource.HISTORY, inference);
        }
        if (!s.tokens().isEmpty() && s.tokens().equals(t.tokens())) {
            return suggestion(source, target, 1.0, MatchSource.EXACT, inference);
        }
        double score = jaroWinkler.apply(s.compact(), t.compact());
        if (score >= threshold) {
            return suggestion(source, target, score, MatchSource.SIMILAR, inference);
        }
        return null;
    }

    private MappingSuggestion suggestion(
            SourceField source,
            TargetField target,
            double score,
            MatchSource matchSource,
            TransformInferrer.Inference inference
    ) {
        return new MappingSuggestion(
                source.name(),
                target.name(),
                score,
                inference.kind(),
                inference.datePattern(),
                inference.constant(),
                matchSource
        );
    }

    private static NameNormalizer.Normalized norm(Map<String, NameNormalizer.Normalized> cache, String name) {
        return cache.computeIfAbsent(name, NameNormalizer::normalize);
    }

    private static String key(String sourcePath, String targetName) {
        return NameNormalizer.normalize(sourcePath).compact() + "|" + NameNormalizer.normalize(targetName).compact();
    }
}
