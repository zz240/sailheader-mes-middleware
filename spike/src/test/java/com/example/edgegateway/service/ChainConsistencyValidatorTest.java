package com.example.edgegateway.service;

import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ChainConsistencyValidator 是“FIELD_MAP 顶层 sourceStep 必须指向链中存在且更早的动作”这条规则的唯一实现。 */
class ChainConsistencyValidatorTest {

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setId((long) index);
        action.setJobId(1L);
        action.setName(kind.name().toLowerCase());
        action.setType(kind);
        action.setActionIndex(index);
        action.setAddition(addition);
        return action;
    }

    @SafeVarargs
    private Map<String, Object> rules(Map<String, Object>... rules) {
        return Map.of("rules", List.of(rules));
    }

    private Map<String, Object> leaf(Integer sourceStep, String target) {
        Map<String, Object> rule = new LinkedHashMap<>();
        if (sourceStep != null) {
            rule.put("sourceStep", sourceStep);
        }
        rule.put("source", "order_no");
        rule.put("target", target);
        rule.put("transform", "STRING");
        return rule;
    }

    @Test
    void passesWhenGroupInheritedStepIsUpstream() {
        Map<String, Object> group = Map.of(
                "sourceStep", 1, "source", "items", "target", "lines", "transform", "STRING",
                "children", List.of(Map.of("source", "code", "target", "code", "transform", "STRING"),
                        Map.of("source", "qty", "target", "qty", "transform", "INT")));

        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.READ_SQL, Map.of()),
                action(2, ActionKind.FIELD_MAP, rules(group))));

        assertThat(problems).isEmpty();
    }

    @Test
    void reportsGroupStepOnceNotPerChild() {
        Map<String, Object> group = Map.of(
                "sourceStep", 1, "source", "items", "target", "lines", "transform", "STRING",
                "children", List.of(Map.of("source", "code", "target", "code", "transform", "STRING"),
                        Map.of("source", "qty", "target", "qty", "transform", "INT")));

        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(2, ActionKind.FIELD_MAP, rules(group))));

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst())
                .contains("动作 2（field_map）")
                .contains("lines")
                .contains("sourceStep=1");
    }

    @Test
    void ignoresChildSourceStepBecauseCompilerInheritsGroupStep() {
        // 组内子规则的 sourceStep 被 JsonataRuleCompiler 忽略（继承组节点）：历史脏数据不该挡住能跑的链
        Map<String, Object> group = Map.of(
                "sourceStep", 1, "source", "items", "target", "lines", "transform", "STRING",
                "children", List.of(leaf(9, "code")));

        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.READ_SQL, Map.of()),
                action(2, ActionKind.FIELD_MAP, rules(group))));

        assertThat(problems).isEmpty();
    }

    @Test
    void passesNullSourceStepAsJobInput() {
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.FIELD_MAP, rules(leaf(null, "sheet_no")))));

        assertThat(problems).isEmpty();
    }

    @Test
    void ignoresActionsWithoutMappingRules() {
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.READ_SQL, Map.of()),
                action(2, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", 7L, "tableName", "t"))));

        assertThat(problems).isEmpty();
    }

    @Test
    void reportsUnparseableMappingConfigInsteadOfThrowing() {
        // 存量脏数据：sourceStep 非数字 → FieldMappingRule.from 抛 NumberFormatException
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(Map.of(
                        "sourceStep", "abc", "source", "order_no", "target", "sheet_no"))))));

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst())
                .contains("动作 2（field_map）")
                .contains("无法解析");
    }

    // ---------- 拆分动作的来源步骤（#140） ----------

    private Map<String, Object> split(Integer sourceStep, String elementKey) {
        Map<String, Object> addition = new LinkedHashMap<>();
        if (sourceStep != null) {
            addition.put("sourceStep", sourceStep);
        }
        addition.put("sourcePath", "lines");
        addition.put("elementKey", elementKey);
        return addition;
    }

    @Test
    void passesSplitSourceStepPointingAtAnEarlierAction() {
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.READ_SQL, Map.of()),
                action(2, ActionKind.SPLIT, split(1, "itemCode"))));

        assertThat(problems).isEmpty();
    }

    @Test
    void splitWithoutSourceStepIsFineBecauseItSplitsTheCurrentBody() {
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of()),
                action(2, ActionKind.SPLIT, split(null, "itemCode"))));

        assertThat(problems).isEmpty();
    }

    @Test
    void reportsSplitSourceStepPointingAtItselfOrLater() {
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.READ_SQL, Map.of()),
                action(2, ActionKind.SPLIT, split(2, "itemCode"))));

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst())
                .contains("动作 2（split）")
                .contains("sourceStep=2")
                .contains("更早");
    }

    @Test
    void reportsSplitSourceStepThatNoLongerExists() {
        // 被引用的那个动作已删（或重排后序号变了）：链级校验要拦下来，不能等跑起来才炸
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(1, ActionKind.READ_SQL, Map.of()),
                action(3, ActionKind.SPLIT, split(2, "itemCode"))));

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst())
                .contains("动作 3（split）")
                .contains("sourceStep=2")
                .contains("不存在");
    }

    @Test
    void reportsUnparseableSplitConfigInsteadOfThrowing() {
        List<String> problems = ChainConsistencyValidator.check(List.of(
                action(2, ActionKind.SPLIT, Map.of("sourceStep", "abc", "elementKey", "itemCode"))));

        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst())
                .contains("动作 2（split）")
                .contains("无法解析");
    }
}
