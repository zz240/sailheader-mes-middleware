package com.example.edgegateway.service;

import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.FieldMappingRule;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作链 sourceStep 引用一致性校验（#89）：FIELD_MAP 顶层规则的 {@code sourceStep} 必须指向链中存在的、
 * 序号更早的动作（{@code 0} = 任务输入，恒合法）。
 *
 * <p>本类只治 FIELD_MAP.sourceStep（bodySourceStep 的写时校验在 {@code ActionService.checkBodySourceStep}，
 * 链级悬空仍靠运行时显式失败兜底）。同一套规则挂三处：写时（{@link ActionService} 新增/修改 FIELD_MAP → 400）、
 * {@code POST /api/jobs/{id}/validate}（{@link JobValidationService} → errors）、
 * 路由启用/重建前（{@link RouteRegistry} → 抛错并交由 {@link RouteLifecycle} 自动停用），
 * 覆盖写时管不到的两类：删除被引用动作留下的悬空引用、动作重排造成的错位。</p>
 *
 * <p>只校验顶层规则：组内子规则的 sourceStep 被 {@code JsonataRuleCompiler} 忽略并继承组节点的 step
 * （历史数据可能残留），对它们报错只会挡住本可正常执行的链。</p>
 */
public final class ChainConsistencyValidator {

    /** 上下文中的任务输入序号（action-chain-design §2）。 */
    private static final int JOB_INPUT_STEP = 0;

    private ChainConsistencyValidator() {
    }

    /**
     * 一条 sourceStep 问题：出错的那条规则 + 说明（不含「动作 N（名字）」那截定位，定位由调用方拼）。
     */
    public record SourceStepProblem(FieldMappingRule rule, String message) {
    }

    /** 校验整条链：每个悬空/错位的 sourceStep 返回一条定位描述（空列表 = 一致）。 */
    public static List<String> check(List<Action> actions) {
        Set<Integer> indexes = new HashSet<>();
        for (Action action : actions) {
            if (action.getActionIndex() != null) {
                indexes.add(action.getActionIndex());
            }
        }
        List<String> problems = new ArrayList<>();
        for (Action action : actions) {
            if (action.getType() != ActionKind.FIELD_MAP || action.getActionIndex() == null) {
                continue;
            }
            List<FieldMappingRule> rules;
            try {
                rules = FieldMappingConfig.from(
                        action.getAddition() == null ? Map.of() : action.getAddition()).rules();
            } catch (RuntimeException e) {
                // 存量脏配置：报成问题而不是抛出，否则 /validate 直接 500、启用也只会炸出内部异常
                problems.add(location(action) + " 字段映射配置无法解析: " + e.getMessage());
                continue;
            }
            if (rules == null) {
                continue;
            }
            Set<Integer> earlier = new HashSet<>(indexes);
            earlier.removeIf(step -> step >= action.getActionIndex());
            for (SourceStepProblem problem : checkRules(rules, action.getActionIndex(), earlier)) {
                problems.add(location(action, problem.rule()) + " 的 " + problem.message());
            }
        }
        return problems;
    }

    /**
     * 一份规则表的 sourceStep 检查——链级校验（上面那个 {@link #check(List)}）与「校验配置」的草稿校验
     * （#103）共用这一份，不各写一遍。
     *
     * @param actionIndex          这份规则所在动作的序号；只知道「哪些更早」而不知道自己的序号时给 null
     * @param earlierActionIndexes 链上执行顺序**更早**的动作序号——sourceStep 只能指向这些（0 = 任务输入恒合法）
     */
    public static List<SourceStepProblem> checkRules(
            List<FieldMappingRule> rules, Integer actionIndex, Set<Integer> earlierActionIndexes) {
        if (rules == null) {
            return List.of();
        }
        List<SourceStepProblem> problems = new ArrayList<>();
        for (FieldMappingRule rule : rules) {
            Integer step = rule.sourceStep();
            if (step == null) {
                continue; // 未声明 = 任务输入（0），恒合法（「漏写」这条提示由草稿校验另出，#103）
            }
            if (actionIndex != null && step >= actionIndex) {
                problems.add(new SourceStepProblem(rule, "sourceStep=" + step
                        + " 必须指向执行顺序更早的动作（当前序号 " + actionIndex + "）"));
            } else if (step != JOB_INPUT_STEP && !earlierActionIndexes.contains(step)) {
                problems.add(new SourceStepProblem(rule, "sourceStep=" + step
                        + " 在动作链中不存在，请填上游动作序号"));
            }
        }
        return problems;
    }

    /** 多条问题拼成一条错误信息（写时 400 与启用前拦截共用）。 */
    public static String describeAll(List<String> problems) {
        return String.join("；", problems);
    }

    /** 统一错误定位格式：动作序号 + 动作名 + 规则 target。 */
    private static String location(Action action, FieldMappingRule rule) {
        return location(action) + "映射规则 " + rule.target();
    }

    private static String location(Action action) {
        return "动作 " + action.getActionIndex() + "（" + action.getName() + "）";
    }
}
