package com.example.edgegateway.mapping.suggest;

import com.example.edgegateway.service.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 映射建议器：同接口替换实现。输入源/目标结构 + 站点映射历史，
 * 让 LLM 输出 JSON 候选；解析失败/超时/异常一律回退规则引擎。
 * LLM 输出仅作候选（matchSource=LLM），需实施工程师确认后才落库。
 */
@Component
public class LlmMappingSuggester implements MappingSuggester {

    private static final String SYSTEM_PROMPT =
            "你是制造业数据对接的字段映射专家。根据源结构、目标字段清单和已确认的历史映射，"
                    + "为每个 required=true 的目标字段推荐匹配的源字段。只输出 JSON 数组，不要任何解释或代码块标记，"
                    + "每项格式 {\"sourcePath\":\"...\",\"targetName\":\"...\",\"transform\":\"STRING|INT|DATE|ENUM|CONSTANT|BOOLEAN|NUMBER\","
                    + "\"datePattern\":\"...\",\"constant\":\"...\",\"enumMap\":{},\"score\":0.0}，"
                    + "每个目标最多 3 个候选，按匹配度降序。找不到匹配就不输出该目标。";

    private static final Set<String> VALID_TRANSFORMS = Set.of(
            "STRING", "INT", "NUMBER", "DATE", "ENUM", "CONSTANT", "BOOLEAN");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final RuleMappingSuggester fallback;

    public LlmMappingSuggester(LlmClient llmClient, ObjectMapper objectMapper, RuleMappingSuggester fallback) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public List<MappingSuggestion> suggest(
            SourceStructure source,
            List<TargetField> targetFields,
            List<HistoryMapping> history) {
        try {
            String output = llmClient.chat(SYSTEM_PROMPT, buildPrompt(source, targetFields, history));
            return top3PerTarget(parse(output, targetFields));
        } catch (Exception e) {
            return fallback.suggest(source, targetFields, history);
        }
    }

    private String buildPrompt(
            SourceStructure source,
            List<TargetField> targetFields,
            List<HistoryMapping> history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceKey", source.key());
        payload.put("sourceFields", source.fields().stream()
                .map(f -> Map.of(
                        "name", f.name(),
                        "type", f.type(),
                        "samples", f.samples() == null ? List.of() : f.samples().stream().limit(3).toList()))
                .toList());
        payload.put("targets", targetFields.stream()
                .map(t -> Map.of("name", t.name(), "required", t.required()))
                .toList());
        payload.put("history", history.stream()
                .map(h -> Map.of("sourcePath", h.sourcePath(), "targetName", h.targetName(), "transform", h.transform()))
                .toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("构造 LLM 提示词失败", e);
        }
    }

    private List<MappingSuggestion> parse(String output, List<TargetField> targetFields) throws Exception {
        String text = output == null ? "" : output.trim();
        text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        Set<String> requiredTargets = targetFields.stream()
                .filter(TargetField::required)
                .map(TargetField::name)
                .collect(Collectors.toSet());
        JsonNode array = objectMapper.readTree(text);
        List<MappingSuggestion> suggestions = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String target = text(node, "targetName");
                if (target == null || !requiredTargets.contains(target)) {
                    continue;
                }
                String transform = text(node, "transform");
                if (transform == null || !VALID_TRANSFORMS.contains(transform)) {
                    transform = "STRING";
                }
                suggestions.add(new MappingSuggestion(
                        text(node, "sourcePath"),
                        target,
                        node.hasNonNull("score") ? node.get("score").asDouble(0.9) : 0.9,
                        TransformKind.valueOf(transform),
                        text(node, "datePattern"),
                        text(node, "constant"),
                        node.has("enumMap") && node.get("enumMap").isObject()
                                ? objectMapper.convertValue(node.get("enumMap"), Map.class)
                                : null,
                        MatchSource.LLM));
            }
        }
        return suggestions;
    }

    private List<MappingSuggestion> top3PerTarget(List<MappingSuggestion> suggestions) {
        Map<String, List<MappingSuggestion>> byTarget = suggestions.stream()
                .collect(Collectors.groupingBy(MappingSuggestion::targetName, LinkedHashMap::new, Collectors.toList()));
        List<MappingSuggestion> result = new ArrayList<>();
        for (List<MappingSuggestion> list : byTarget.values()) {
            list.sort(Comparator.comparingDouble(MappingSuggestion::score).reversed());
            result.addAll(list.stream().limit(3).toList());
        }
        return result;
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
