package com.example.edgegateway.mapping.suggest;

import com.example.edgegateway.service.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMappingSuggesterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SourceStructure source = new SourceStructure("db:po_order", List.of(
            new SourceField("order_no", "VARCHAR", false, List.of("PO-1", "PO-2")),
            new SourceField("vendor_code", "VARCHAR", true, List.of("V001", "V002")),
            new SourceField("status", "VARCHAR", true, List.of("ACTIVE", "CLOSED"))));

    private final List<TargetField> targets = List.of(
            new TargetField("sheet_no", true),
            new TargetField("vendor_code", true),
            new TargetField("status", true));

    @Test
    void parsesLlmJsonAndMarksLlm() {
        LlmClient fake = (system, user) -> """
                [{"sourcePath":"order_no","targetName":"sheet_no","transform":"STRING","score":0.95},
                 {"sourcePath":"order_no","targetName":"sheet_no","transform":"STRING","score":0.8},
                 {"sourcePath":"vendor_code","targetName":"vendor_code","transform":"STRING","score":0.9},
                 {"sourcePath":"status","targetName":"status","transform":"ENUM","enumMap":{"ACTIVE":1,"CLOSED":0},"score":0.9}]
                """;
        LlmMappingSuggester suggester = new LlmMappingSuggester(fake, objectMapper, new RuleMappingSuggester());

        List<MappingSuggestion> suggestions = suggester.suggest(source, targets, List.of());

        assertThat(suggestions).hasSize(4);
        assertThat(suggestions).allMatch(s -> s.matchSource() == MatchSource.LLM);
        assertThat(suggestions).filteredOn(s -> s.targetName().equals("sheet_no"))
                .extracting(MappingSuggestion::score)
                .containsExactly(0.95, 0.8); // top3 按得分降序
        assertThat(suggestions).anyMatch(s -> s.targetName().equals("status") && s.enumMap() != null);
    }

    @Test
    void fallsBackToRuleEngineWhenLlmThrows() {
        LlmClient fake = (system, user) -> {
            throw new RuntimeException("timeout");
        };
        LlmMappingSuggester suggester = new LlmMappingSuggester(fake, objectMapper, new RuleMappingSuggester());

        List<MappingSuggestion> suggestions = suggester.suggest(source, targets, List.of());

        assertThat(suggestions).allMatch(s -> s.matchSource() != MatchSource.LLM);
        assertThat(suggestions).anyMatch(s -> s.targetName().equals("vendor_code")
                && s.matchSource() == MatchSource.EXACT);
    }

    @Test
    void fallsBackWhenLlmOutputIsNotJson() {
        LlmClient fake = (system, user) -> "抱歉，我不确定";
        LlmMappingSuggester suggester = new LlmMappingSuggester(fake, objectMapper, new RuleMappingSuggester());

        List<MappingSuggestion> suggestions = suggester.suggest(source, targets, List.of());

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).allMatch(s -> s.matchSource() != MatchSource.LLM);
    }

    @Test
    void evalCanMeasureLlmAutoFillRate() {
        // 评测口径可直接套用：fake 客户端返回 ground truth，配对率即 LLM 实现基线
        LlmClient fake = (system, user) -> """
                [{"sourcePath":"order_no","targetName":"sheet_no","transform":"STRING","score":1.0},
                 {"sourcePath":"vendor_code","targetName":"vendor_code","transform":"STRING","score":1.0},
                 {"sourcePath":"status","targetName":"status","transform":"ENUM","score":1.0}]
                """;
        LlmMappingSuggester suggester = new LlmMappingSuggester(fake, objectMapper, new RuleMappingSuggester());

        List<MappingSuggestion> suggestions = suggester.suggest(source, targets, List.of());
        long paired = suggestions.stream()
                .filter(s -> NameNormalizer.normalize(s.sourcePath()).compact()
                        .equals(NameNormalizer.normalize(s.targetName()).compact()))
                .count();

        assertThat(paired).isEqualTo(2); // order_no→sheet_no 是业务重命名，不算配对；其余 2 个同名
    }
}
