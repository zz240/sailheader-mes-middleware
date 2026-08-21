package com.example.edgegateway.mapping.suggest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DS-10.2 S4：数组结构组建议（children 相对路径）+ 单值样例 STRING 回归。 */
class RuleMappingSuggesterGroupTest {

    private final RuleMappingSuggester suggester = new RuleMappingSuggester();

    private static SourceStructure bomSource() {
        return new SourceStructure("mq:e2e", List.of(
                new SourceField("product.productCode", "STRING", false, List.of("PRD-001")),
                new SourceField("product.bomItems[0].materialCode", "STRING", false, List.of("MAT-718H")),
                new SourceField("product.bomItems[0].materialName", "STRING", false, List.of("718H模具钢")),
                new SourceField("product.bomItems[0].qty", "INT", false, List.of(1, 2)),
                new SourceField("product.bomItems[0].unit", "STRING", false, List.of("PCS"))));
    }

    @Test
    void arrayTargetProducesGroupSuggestionWithRelativeChildren() {
        List<MappingSuggestion> suggestions = suggester.suggest(
                bomSource(),
                List.of(new TargetField("bom.bomLines", false, true)),
                List.of());

        assertThat(suggestions).hasSize(1);
        MappingSuggestion group = suggestions.get(0);
        assertThat(group.sourcePath()).isEqualTo("product.bomItems");
        assertThat(group.targetName()).isEqualTo("bom.bomLines");
        assertThat(group.matchSource()).isEqualTo(MatchSource.ARRAY);
        assertThat(group.children()).hasSize(4);

        MappingSuggestion materialCode = group.children().stream()
                .filter(c -> c.sourcePath().equals("materialCode"))
                .findFirst().orElseThrow();
        assertThat(materialCode.targetName()).isEqualTo("materialCode");
        assertThat(materialCode.transform()).isEqualTo(TransformKind.STRING);

        MappingSuggestion qty = group.children().stream()
                .filter(c -> c.sourcePath().equals("qty"))
                .findFirst().orElseThrow();
        assertThat(qty.transform()).isEqualTo(TransformKind.INT);
    }

    @Test
    void nonMatchingArrayTargetYieldsNoGroupSuggestion() {
        List<MappingSuggestion> suggestions = suggester.suggest(
                bomSource(),
                List.of(new TargetField("totally.unrelated.list", false, true)),
                List.of());

        assertThat(suggestions).isEmpty();
    }

    /** 回归（DS-10.4）：单值样例建议 STRING，不推荐 CONSTANT。 */
    @Test
    void singleSampleFieldSuggestsStringNotConstant() {
        List<MappingSuggestion> suggestions = suggester.suggest(
                new SourceStructure("sample", List.of(
                        new SourceField("orderNo", "STRING", true, List.of("PO-001")))),
                List.of(new TargetField("order_no", true, false)),
                List.of());

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).transform()).isEqualTo(TransformKind.STRING);
    }
}
