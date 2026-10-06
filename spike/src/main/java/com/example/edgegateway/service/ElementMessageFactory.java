package com.example.edgegateway.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 元素 → 已初始化消息（#139，ADR-0017 的 R1 抽取）。
 *
 * <p>把「一批元素变成 N 条已初始化消息」收在一处：**消息标识、业务键、父消息标识、批内序号**的
 * 初始化归它管，外层字段继承（拆分那档要把父消息的外层字段摊平进子消息）也归它管。
 * <b>策略部分留在原处</b>——什么时候开读库循环、批尾提交水位、split 之前结算动作耗时、
 * 失败步挂批量占位标识、批号什么时候换，都还在 {@code RouteGenerator} 里。这一抽是纯重构，
 * **行为零变化**。</p>
 *
 * <p>为什么值得单独成 module：这条规则原先硬编码在「读库分批」那一条分支里，而拆分与合并两个动作
 * 要的是同一件事（一条消息里的数组摊成 N 条 / 一批消息各自初始化）。抽出来之后它可以**脱离整链单测**
 * （见 {@code ElementMessageFactoryTest}），不必跑通一条 Camel 路由才能验。</p>
 *
 * <p><b>批内序号从 1 起</b>（「这批里第几条」），而且只有 {@link ElementSpec#split} 那一档才写：
 * 读取批里「第几条」没有额外语义，读库产生的那些行留空——空不是 0（ADR-0017 决策 2）。拆分与合并
 * 要的**父消息标识、批内序号、外层字段继承**因此现在就实现好了：票面要求这个 module 的单测覆盖
 * 那三样（#139 验收项），但这一票**没有调用方**——拆分（#140）与合并（#142）是后面两张票。</p>
 *
 * <p>纯逻辑、无依赖，所以是静态工具类（与 {@code SqlDialects} / {@code DatePatterns} 同一档），
 * 不进 Spring 容器、也不占 {@code RouteGenerator} 的构造参数。</p>
 */
public final class ElementMessageFactory {

    private ElementMessageFactory() {
    }

    /**
     * 一条刚初始化的消息：标识、业务键、父消息标识、批内序号、消息体。
     *
     * <p>{@code body} 可能已经被摊入了父消息的外层字段（拆分那档的 {@code keepParentFields}），
     * 所以它与传进来的那个元素不是同一个对象——要用消息体请用这一格。</p>
     */
    public record InitializedMessage(String messageId, String businessKey, String parentMessageId,
                                     Integer batchSeq, Object body) {
    }

    /**
     * 这一批元素怎么变成消息。
     *
     * @param businessKeyConfig  业务键取哪个字段（点分 JSON 路径 / 单段列名），空 = 不提业务键
     * @param carriedBusinessKey 已经算好的业务键（重投入口那种「沿用原业务键」）；非空时压过 {@code businessKeyConfig}
     * @param parentBody         父消息的报文（外层字段继承的出处）；不是拆分那档传 {@code null}
     * @param parentMessageId    拆出来的每条都带它；不是拆分那档传 {@code null}
     * @param keepParentFields   外层字段是否摊平进子消息
     * @param numbered           要不要给这一批编号（拆分 / 合并要；读取批不要）
     */
    public record ElementSpec(String businessKeyConfig, String carriedBusinessKey, Object parentBody,
                              String parentMessageId, boolean keepParentFields, boolean numbered) {

        /**
         * 读库那一档：一个元素一条消息，没有父消息、也不编号。
         *
         * <p>{@code carriedBusinessKey} 给重投入口用——重投时业务键已经由入口写好了，不再从报文里提
         * （重投的是失败步的输入快照，那份报文未必还带得出业务键）。</p>
         */
        public static ElementSpec read(String businessKeyConfig, String carriedBusinessKey) {
            return new ElementSpec(businessKeyConfig, carriedBusinessKey, null, null, false, false);
        }

        /** 拆分那一档：每条子消息带父消息标识与批内序号，外层字段按 {@code keepParentFields} 摊平。 */
        public static ElementSpec split(String elementKeyConfig, String parentMessageId, Object parentBody,
                                        boolean keepParentFields) {
            return new ElementSpec(elementKeyConfig, null, parentBody, parentMessageId, keepParentFields, true);
        }
    }

    /**
     * 一个拆分元素 → 一条子消息（#140）：**取 {@link ElementSpec#split} 那一档**——父消息标识、外层
     * 字段继承、批内序号三样都要，序号按元素在数组里的位置从 1 起。
     *
     * <p>它单独有个入口，是为了让调用方（路由生成器）不必自己拼 {@link ElementSpec}：
     * 「拆出来的子消息要编号」这件事是拆分这一档的固有语义，谁调用都不该漏（漏了就是
     * {@code batch_seq} 全空、右详情里那批 chip 没有序号）。</p>
     *
     * @param position 元素在数组里的位置（**从 1 起**）
     */
    public static InitializedMessage splitChild(Object element, String elementKeyConfig, String parentMessageId,
                                                Object parentBody, boolean keepParentFields, int position) {
        return initialize(element,
                ElementSpec.split(elementKeyConfig, parentMessageId, parentBody, keepParentFields), position);
    }

    /** 现生成一个批号：每一次基数变化动作执行生成一个；读库那档每读一批一个（#139 / ADR-0017 决策 2）。 */
    public static String newBatchId() {
        return UUID.randomUUID().toString();
    }

    /** 一个元素 → 一条消息（读库那条路就是这一档：一行一条消息，逐行走）。 */
    public static InitializedMessage of(Object element, ElementSpec spec) {
        return initialize(element, spec, null);
    }

    /**
     * 一批元素 → N 条消息，**顺序就是批内序号的顺序**（拆分与合并那一档；
     * 读库那条路一行一次地走 {@link #of}，因为它的消息是在 Camel 的 split 里逐条建的）。
     */
    public static List<InitializedMessage> all(List<?> elements, ElementSpec spec) {
        List<InitializedMessage> messages = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            messages.add(initialize(elements.get(i), spec, spec.numbered() ? i + 1 : null));
        }
        return messages;
    }

    /**
     * 按点分路径取值，**路径遍历只此一处**：{@link #businessKey}（元素 → 业务键）与路由生成器
     * 解析拆分来源（{@code sourcePath}）问的是同一件事，写法不该有两份。
     *
     * <p>段先当 Map 的键；纯数字段在 List 上当下标（{@code lines.0.code} 这种——只有来源路径用得上，
     * 业务键只认纯名字段）。走不通（中间不是 Map/List、下标越界、键不存在）回 {@code null}，
     * 不抛：取不到就是取不到，由调用方决定怎么说这句话。</p>
     */
    public static Object valueAt(Object data, String path) {
        if (path == null || path.isBlank()) {
            return data;
        }
        Object value = data;
        for (String part : path.split("\\.")) {
            if (value instanceof Map<?, ?> map) {
                value = map.get(part);
            } else if (value instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                value = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return value;
    }

    /**
     * 从消息数据里提业务键：支持点分 JSON 路径与单段列名。
     *
     * <p>它是「元素 → 消息」这一步算出来的（原先在 {@code MessageHistoryService} 上），所以住在这里；
     * 配置为空、路径断了、取到的是 {@code null} 都回 {@code null}——不提就是没有业务键，不编一个空串。</p>
     */
    public static String businessKey(Object data, String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        Object value = valueAt(data, config);
        return value == null ? null : String.valueOf(value);
    }

    private static InitializedMessage initialize(Object element, ElementSpec spec, Integer batchSeq) {
        String businessKey = spec.carriedBusinessKey() != null
                ? spec.carriedBusinessKey()
                : businessKey(element, spec.businessKeyConfig());
        return new InitializedMessage(
                UUID.randomUUID().toString(),
                businessKey,
                spec.parentMessageId(),
                batchSeq,
                inherit(element, spec));
    }

    /**
     * 外层字段继承：父消息的字段摊平进子消息，**子消息自己的同名字段赢**（元素是这条消息的主体，
     * 父消息只补它没有的那些）。
     *
     * <p>两边不是键值对（父消息不是对象 / 元素不是对象）时**原样返回元素**，不强行包一层：
     * 硬包会把一串标量元素变成 {@code {"value": …}}，那是无中生有。</p>
     */
    private static Object inherit(Object element, ElementSpec spec) {
        if (!spec.keepParentFields() || !(spec.parentBody() instanceof Map<?, ?> parent)
                || !(element instanceof Map<?, ?> child)) {
            return element;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        parent.forEach((key, value) -> merged.put(String.valueOf(key), value));
        child.forEach((key, value) -> merged.put(String.valueOf(key), value));
        return merged;
    }
}
