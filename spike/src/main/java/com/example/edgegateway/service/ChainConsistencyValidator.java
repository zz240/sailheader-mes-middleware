package com.example.edgegateway.service;

import com.example.edgegateway.config.AggregateConfig;
import com.example.edgegateway.config.OneToManyConfig;
import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.FieldMappingRule;
import com.example.edgegateway.config.OneToManyConfig;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作链 sourceStep 引用一致性校验（#89）：FIELD_MAP 顶层规则的 {@code sourceStep} 与拆分动作
 * （#140）的 {@code sourceStep} 必须指向链中存在的、序号更早的动作（{@code 0} = 任务输入，恒合法）。
 *
 * <p>本类只治「跨步骤引用的两个来源」——{@code FIELD_MAP} 顶层规则的 {@code sourceStep} 与拆分动作
 * （#140）的 {@code sourceStep}；{@code bodySourceStep} 的写时校验在
 * {@code ActionService.checkBodySourceStep}（判据同一份 {@code stepProblem}，链级悬空仍靠运行时
 * 显式失败兜底）。同一套规则挂三处：写时（{@link ActionService} 新增/修改 FIELD_MAP 或拆分 → 400）、
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
            if (action.getActionIndex() == null) {
                continue;
            }
            if (action.getType() == ActionKind.ONE_TO_MANY) {
                String problem = checkOneToMany(action, indexes, actions);
                if (problem != null) {
                    problems.add(problem);
                }
                continue;
            }
            if (action.getType() == ActionKind.AGGREGATE) {
                // 合并没有「来源步骤」可指（它攒的是**走到它这里的那批消息**），链级要管的是配置本身
                // 能不能解析：脏配置报成问题而不是抛出去（与拆分那一档同一处理，否则 /validate 500）
                String problem = checkAggregateConfig(action);
                if (problem != null) {
                    problems.add(problem);
                }
                continue;
            }
            if (action.getType() != ActionKind.FIELD_MAP) {
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
     * 一分多的来源步骤与前提（两档，校验按**档**走）：
     *
     * <ul>
     *   <li><b>拆分档</b>：{@code sourceStep} 可空（空 = 取当前消息体），填了就必须指向链中**存在且序号
     *       更早**的动作——与 FIELD_MAP 的 sourceStep 同一条口径、同一批闸口；</li>
     *   <li><b>还原档</b>：{@code sourceStep} **必填**（它必须知道去哪儿取回应），同样要指向更早的动作；
     *       此外它**要求链上有上游合并动作**——还原靠成员清单（{@code edge.batchManifest}）回到原来那几条
     *       消息，没有合并就没有那份清单（写时 400 / {@code /validate} / 启用前都会拦）。</li>
     * </ul>
     *
     * <p>为什么不能省这道校验：来源指错时运行期那一步会「取不到数据 / 回应」当场失败，
     * 但那是**跑起来才知道**；配置期拦下来才符合既有那套。</p>
     *
     * @return 一条问题描述（定位已拼好），没有问题时 {@code null}
     */
    private static String checkOneToMany(Action action, Set<Integer> indexes, List<Action> actions) {
        OneToManyConfig many;
        try {
            many = OneToManyConfig.from(action.getAddition() == null ? Map.of() : action.getAddition());
        } catch (RuntimeException e) {
            // 与字段映射那一档同一处理：存量脏配置报成问题，不抛出去（否则 /validate 500）
            return location(action) + " 一分多配置无法解析: " + e.getMessage();
        }
        if (many.sourceStep() != null) {
            String problem = stepProblem("sourceStep", many.sourceStep(), action.getActionIndex(), indexes);
            if (problem != null) {
                return location(action) + " 的 " + problem;
            }
        }
        if (many.isRestore() && actions.stream().noneMatch(candidate ->
                candidate.getType() == ActionKind.AGGREGATE
                        && candidate.getActionIndex() != null
                        && candidate.getActionIndex() < action.getActionIndex())) {
            return location(action) + " 的还原档要求链上有上游合并动作（成员清单是它与出站之间唯一的联系）";
        }
        return null;
    }

    /**
     * 合并动作的配置（#142）：链级只管「这份配置读不读得出来」——脏配置报成问题而不是抛出去
     * （与拆分那一档同一处理，否则 {@code /validate} 直接 500）。
     *
     * <p>它没有源步骤：合并攒的是**走到它这里的那批消息**，所以那类「悬空引用」在它身上不存在；
     * 六个字段的必填与取值边界（方法只能是带 body 的那几个、三个键得是纯字段名）归
     * {@link AggregateConfig#validate()}，写时与 {@code /validate} 各自都会调到它。</p>
     */
    private static String checkAggregateConfig(Action action) {
        try {
            AggregateConfig.from(action.getAddition() == null ? Map.of() : action.getAddition());
            return null;
        } catch (RuntimeException e) {
            return location(action) + " 合并配置无法解析: " + e.getMessage();
        }
    }

    /**
     * 一条跨步骤引用的问题描述（不含定位）——**一处判据**：三个消费方（字段映射的规则、拆分的
     * {@code sourceStep}、以及写时那条 {@code bodySourceStep}）问的是同一个问题「这一步能不能指向那一步」，
     * 两个判据与两句文案因此只写在这一处，不各写一遍。
     *
     * @param field                这个问题挂在哪个配置字段上（进错误文案，如 {@code sourceStep} / {@code bodySourceStep}）
     * @param step                 被引用的动作序号
     * @param actionIndex          发起引用的那个动作的序号；只知道「哪些更早」而不知道自己的序号时给 null
     * @param earlierActionIndexes 链上存在的动作序号（用来判悬空；{@code 0} = 任务输入恒合法）
     * @return 问题描述，没有问题（或未声明引用）时 {@code null}
     */
    static String stepProblem(String field, Integer step, Integer actionIndex, Set<Integer> earlierActionIndexes) {
        if (step == null) {
            return null; // 未声明引用：字段映射当任务输入（0）、拆分当当前消息体，都恒合法
        }
        if (actionIndex != null && step >= actionIndex) {
            return field + "=" + step + " 必须指向执行顺序更早的动作（当前序号 " + actionIndex + "）";
        }
        if (step != JOB_INPUT_STEP && !earlierActionIndexes.contains(step)) {
            return field + "=" + step + " 在动作链中不存在，请填上游动作序号";
        }
        return null;
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
            String problem = stepProblem("sourceStep", rule.sourceStep(), actionIndex, earlierActionIndexes);
            if (problem != null) {
                problems.add(new SourceStepProblem(rule, problem));
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
