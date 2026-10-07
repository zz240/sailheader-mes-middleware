package com.example.edgegateway.service;

import com.example.edgegateway.config.action.OneToManyConfig;
import com.example.edgegateway.model.Action;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.builder.ExpressionBuilder;
import org.apache.camel.model.ProcessorDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 拆分动作的片段（#145 从 {@code RouteGenerator} 搬出；语义见 #140 / ADR-0017 决策 4）：
 * **自己记一行**消息步 + 把数组摊成 N 条子消息。
 *
 * <p>三件事按顺序发生：</p>
 * <ol>
 *   <li><b>解析来源</b>：{@code sourceStep} 空 = 当前消息体，否则 {@code context[sourceStep]}；
 *       再按 {@code sourcePath} 走到那片数组。解析不出来（步骤没执行 / 路径断了 / 不是数组）
 *       当场显式失败——绝不静默拆出 0 条。</li>
 *   <li><b>记一行消息步</b>：输入是**父消息**、输出是**子消息们**（那片数组），挂父消息自己的
 *       消息标识；这一行带的是**上游那个批号**（批号在转手之前还属于上一段）。</li>
 *   <li><b>换号并摊开</b>：现生成一个新批号 + 记住父消息标识，再按解析出来的那片数组 split；
 *       两格都写在进入 split 之前，于是每条子消息**继承**新批号、并记得自己从哪条消息拆出来。</li>
 * </ol>
 *
 * <p>拆完之后链上仍是「一条一条走」：其后的片段逐个处理每条子消息，失败即停 / 重试退避 /
 * 水位推进这些既有语义一个字不改。</p>
 */
@Component
class SplitFragment {

    private final StepLedger ledger;

    SplitFragment(StepLedger ledger) {
        this.ledger = ledger;
    }

    /** 追加拆分片段。这一层是 split，**由调用方（装配）按层数记账**，收尾时统一 {@code end()} 回来。 */
    ProcessorDefinition<?> appendSplit(ProcessorDefinition<?> current, Action action, OneToManyConfig split) {
        int index = action.getActionIndex();
        return current
                .process(ex -> {
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    ledger.beginStep(ex); // #134：计时起点打在解析与摊开之前
                    Object input = ex.getMessage().getBody();
                    Object source = resolveSplitSource(ex, split);
                    List<?> elements = splitArray(source, split);
                    ex.setProperty(MessageHistoryService.STEP_INPUT, input);
                    // 这一批的两样东西各挂一个属性，都是给 split 之内那条子消息用的：
                    //   source —— keepParent 摊平的是**父消息**（按配置 sourceStep + sourcePath 取到的
                    //              那个对象，不是数组里的某个元素）的外层字段；
                    //   elements —— "这一步拆出了几条"的现场（批内序号不靠它，那用 split 的迭代下标）。
                    // 不复用 edge.stepInput：它是"这一步的输入快照"，语义不同（那一格记的是父消息本身）
                    ex.setProperty(RouteKeys.SPLIT_SOURCE, source);
                    // 这一行本该摊出几条（#142）：合并动作判「齐了没有」要用它；同一行拆出来的 k 条
                    // 带的是同一个数（去重靠父消息标识），写在进 split 之前——子 exchange 继承它
                    ex.setProperty(RouteKeys.BATCH_EXPECTED, elements.size());
                    // 这一行的输入是父消息（就是当下的消息体）、输出是子消息们（那片数组），
                    // 挂的是**父消息自己的标识**（它自己那一行，谱系上就是"父"），带的是**上游那个批号**
                    // （批号还没转手，这一段仍属上一批；MQ 入口那种没有上游批号的链，这一格就是空）。
                    // 所以它要在换号之前记；data 显式给 elements（而不是让默认那条路取 body）：
                    // 这一行的输出是拆出来的那些子消息，不是父消息自己
                    String parentMessageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
                    ex.setProperty(MessageHistoryService.PARENT_MESSAGE_ID, parentMessageId);
                    ledger.recordSuccessStep(ex, action, input, elements);
                    // 批号在这里**换手**（ADR-0017 决策 2/3）：新号给这一批子消息，父消息标识继续往下传
                    // 记谱系。两条都写在进入 split 之前——子 exchange 继承它们（与 READ_SQL 那条路同一手法）
                    ex.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
                    ex.setProperty(MessageHistoryService.PARENT_MESSAGE_ID, parentMessageId);
                    // 父消息标识已经记下来了；从那以后这一格不再代表"当前消息"（子消息各自有自己的标识），
                    // 留着会让「失败步挂哪条消息」挂错
                    ex.removeProperty(MessageHistoryService.MESSAGE_ID);
                })
                // **不用 body()**：Camel 的 split(body()) 遇到 Map 会按 entry 摊开（每条子消息拿到一个
                // Map.Entry），而拆分这一步的消息体既可能是数组、也可能是装着数组的报文。这里显式按
                // 「来源步骤 + 路径」现解析那片数组，与上面记行用的是同一个解析（同一份配置、同一个答案）。
                // 写成匿名类而不是 lambda：Expression 的 evaluate 带自己的类型参数，不是函数式接口
                .split(new Expression() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T evaluate(Exchange ex, Class<T> type) {
                        return (T) splitArray(resolveSplitSource(ex, split), split);
                    }
                })
                .process(ex -> beginChildMessage(ex, split));
    }

    /**
     * 拆分**来源对象**：{@code sourceStep} 空取当前消息体、否则取 {@code context[sourceStep]}。
     *
     * <p>它是两件事的共同出处：按 {@code sourcePath} 走进去得到那片数组，以及 {@code keepParent}
     * 要摊平的**父消息外层字段**（父消息就是它，不是数组里的某个元素）。</p>
     *
     * <p>取不到就**当场显式失败**：步骤没执行（被断点跳过）或当前消息体为空——两种都是配置或链的
     * 问题，静默拆出 0 条只会让运行"成功"而一条数据都不落库。</p>
     */
    private Object resolveSplitSource(Exchange ex, OneToManyConfig split) {
        Object source = split.sourceStep() == null
                ? ex.getMessage().getBody()
                : StepLedger.context(ex).get(split.sourceStep());
        if (source == null) {
            throw new IllegalStateException("拆分的来源没有数据"
                    + (split.sourceStep() == null ? "（当前消息体为空）"
                    : "（sourceStep=" + split.sourceStep() + " 未执行或被断点跳过）"));
        }
        return source;
    }

    /**
     * 来源里那片数组（{@code sourcePath} 走进去；空路径 = 来源本身就是数组）。取不到、或取到的不是
     * 数组，同样当场显式失败——理由见 {@link #resolveSplitSource}。
     */
    private static List<?> splitArray(Object source, OneToManyConfig split) {
        String where = split.sourcePath() == null || split.sourcePath().isBlank()
                ? "（来源本身）" : split.sourcePath();
        Object array = ElementMessageFactory.valueAt(source, split.sourcePath());
        if (array == null) {
            throw new IllegalStateException("拆分的来源路径取不到数据: " + where);
        }
        if (!(array instanceof List<?> list)) {
            throw new IllegalStateException(
                    "拆分的来源不是数组（是 " + array.getClass().getSimpleName() + "）: " + where);
        }
        return list;
    }

    /**
     * 一条子消息的初始化：消息标识现生成、业务键取自元素里的配置路径、批内序号按
     * 元素在数组里的顺序（从 1 起）、父消息标识与批号继承父 exchange 上那两格。
     *
     * <p>{@code keepParent} 摊平的是**父消息**（{@link RouteKeys#SPLIT_SOURCE}，也就是按配置取到的
     * 那个对象）的外层字段，不是数组里某个元素的；而且它**只影响 body**——父消息标识照记，所以
     * 「改 keepParent 不影响谱系」。</p>
     */
    private void beginChildMessage(Exchange ex, OneToManyConfig split) {
        Object element = ex.getMessage().getBody();
        Object parentBody = ex.getProperty(RouteKeys.SPLIT_SOURCE, Object.class);
        String parentMessageId = ex.getProperty(MessageHistoryService.PARENT_MESSAGE_ID, String.class);
        // 批内序号 = 元素在数组里的位置，**用 split 自己的迭代下标**（从 0 起，+1 成"第几条"）。
        // 不按值回头查元素的位置：同一个元素在数组里出现两次时会查回第一个，两条子消息都算"第 1 条"
        Integer splitIndex = ex.getProperty(Exchange.SPLIT_INDEX, Integer.class);
        int position = splitIndex == null ? 1 : splitIndex + 1;
        ElementMessageFactory.InitializedMessage child = ElementMessageFactory.splitChild(
                element, split.elementKey(), parentMessageId, parentBody, split.keepParent(), position);
        ex.setProperty(MessageHistoryService.MESSAGE_ID, child.messageId());
        ex.setProperty(MessageHistoryService.BUSINESS_KEY, child.businessKey());
        // 批内序号在 split **之内**再写一次：Camel 的 split 拷进子 exchange 的属性是有选择的
        // （批号与父消息标识能过来，序号过不来），写在里面才是每条子消息确定拿到的那一次
        ex.setProperty(MessageHistoryService.BATCH_SEQ, child.batchSeq());
        // 链上"当前数据"就是这条子消息的 body（外层字段按配置摊平过了）
        ex.getMessage().setBody(child.body());
        // 拆分那一步的输出：拆之前是整片数组，拆之后**每条子消息看到的自己那一片**——
        // 下游 FIELD_MAP 用 sourceStep=拆分序号 时取到的就是自己那个元素
        StepLedger.putContext(ex, ex.getProperty(MessageHistoryService.ACTION_INDEX, Integer.class), child.body());
    }
}
