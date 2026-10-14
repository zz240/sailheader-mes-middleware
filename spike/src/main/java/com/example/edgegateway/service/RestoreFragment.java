package com.example.edgegateway.service;

import com.example.edgegateway.config.action.OneToManyConfig;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.BatchStamp;
import org.apache.camel.Exchange;
import org.apache.camel.builder.ExpressionBuilder;
import org.apache.camel.model.ProcessorDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 还原动作的片段（#145 从 {@code RouteGenerator} 搬出；语义见 #142 / ADR-0017 决策 7）：
 * 把出站拿回来的响应按配对字段**摊回原来那 N 条消息**（1 → N）。
 *
 * <p>与拆分那一档同一套记账：这一层是 split，后续动作追加在它里面逐条跑，收尾时按层数统一退回
 * （层数记账归装配处 {@code RouteGenerator}）。区别在**身份**：拆分会新造消息、换批号；还原沿用
 * 成员清单里那几条（消息标识 / 业务键 / 父消息标识 / 批内序号），**也不换批号**——它是把这一批
 * 还原回成员，不是新的一批。</p>
 *
 * <p><b>同成同败</b>：有一条没配上（响应里没有它的业务键）就算整批失败——先给这一批每条记一行
 * FAILED（同因，各自带自己那一片），再抛出去让这一批失败，绝不留「看着成功其实只回了一部分」的行。</p>
 */
@Component
class RestoreFragment {

    private final StepLedger ledger;
    private final MessageHistoryService messageHistory;

    RestoreFragment(StepLedger ledger, MessageHistoryService messageHistory) {
        this.ledger = ledger;
        this.messageHistory = messageHistory;
    }

    /** 追加还原片段。这一层是 split，**由调用方（装配）按层数记账**，收尾时统一 {@code end()} 回来。 */
    ProcessorDefinition<?> appendRestore(ProcessorDefinition<?> current, Action action, OneToManyConfig restore) {
        int index = action.getActionIndex();
        return current
                .process(ex -> {
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    ledger.beginStep(ex); // #134：计时起点打在配对之前
                    prepareRestore(ex, action, restore, index);
                })
                .split(ExpressionBuilder.bodyExpression())
                    .process(this::restoreCarrier);
    }

    /**
     * 配对 + 给这一批每条各记一行：输入是**自己那一片请求**、输出是**配给自己的那一份响应**，
     * 批内序号沿用攒批顺序。
     *
     * <p>缺成员清单（上游没有合并动作）时立即失败：还原只能接在「合并 + 出站」之后。</p>
     */
    private void prepareRestore(Exchange ex, Action action, OneToManyConfig restore, int index) {
        List<AggregateBatch.Carrier> carriers = ex.getProperty(RouteKeys.BATCH_MANIFEST, List.class);
        if (carriers == null || carriers.isEmpty()) {
            throw new IllegalStateException(
                    "还原这一步的上游没有合并动作（没有成员清单）：还原只能接在「合并 + 出站」之后");
        }
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        String batchId = ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class);
        Object source = StepLedger.context(ex).get(restore.sourceStep());
        Object response = ElementMessageFactory.valueAt(source, restore.sourcePath());
        if (response == null) {
            throw new IllegalStateException("还原取不到回应：sourceStep=" + restore.sourceStep()
                    + (restore.sourcePath() == null || restore.sourcePath().isBlank() ? "" : "（路径 " + restore.sourcePath() + "）"));
        }
        messageHistory.requireCaptureFitsSnapshot(index, response);
        Long durationMs = ledger.stepDuration(ex);
        Map<String, Object> paired = AggregateBatch.pair(response, restore.resultKeyField(), carriers);
        List<String> missing = carriers.stream()
                .filter(carrier -> !paired.containsKey(carrier.messageId()))
                .map(carrier -> carrier.businessKey() == null ? "（这一条没有业务键）" : carrier.businessKey())
                .toList();
        if (!missing.isEmpty()) {
            // 这一步的失败交给统一的失败那条路：它按成员清单给这一批每条记一行 FAILED，
            // 外加这一步自己那一条（信封消息那一行）——失败行一律按**消息粒度**记（#147）
            throw new IllegalStateException("还原的响应里没有配对到业务键 " + missing + " 的那一条（配对字段 "
                    + restore.resultKeyField() + "）");
        }
        List<AggregateBatch.Carrier> settled = new ArrayList<>();
        int seq = 0;
        for (AggregateBatch.Carrier carrier : carriers) {
            seq++;
            Object slice = paired.get(carrier.messageId());
            messageHistory.recordSuccess(runId, carrier.messageId(), index, action.getName(), carrier.body(),
                    slice, carrier.businessKey(), ledger.attemptsOnSuccess(ex), durationMs,
                    new BatchStamp(batchId, seq, carrier.parentMessageId()));
            settled.add(carrier.settled(seq, restore.attachAs(), slice, index));
        }
        ex.getMessage().setBody(settled);
    }

    /** 还原：把成员清单里那一条的现场装回 exchange，后续动作照旧逐条跑。 */
    private void restoreCarrier(Exchange ex) {
        AggregateBatch.Carrier carrier = ex.getMessage().getBody(AggregateBatch.Carrier.class);
        ex.setProperty(MessageHistoryService.MESSAGE_ID, carrier.messageId());
        ex.setProperty(MessageHistoryService.BUSINESS_KEY, carrier.businessKey());
        ex.setProperty(MessageHistoryService.BATCH_SEQ, carrier.batchSeq());
        ex.setProperty(MessageHistoryService.PARENT_MESSAGE_ID, carrier.parentMessageId());
        ex.setProperty(MessageHistoryService.STEP_INPUT, carrier.body());
        ex.setProperty(RouteKeys.CONTEXT, new LinkedHashMap<>(carrier.context()));
        ex.getMessage().setBody(carrier.body());
    }

}
