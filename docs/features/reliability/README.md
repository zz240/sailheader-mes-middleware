# 可靠性（失败/重试/消息历史/断点重投）

失败即停 + 按任务配置重试退避 + 消息历史记录与断点重投；不承诺跨系统补偿事务。

## 当前设计（现状）

- **失败即停（ADR-0003）**：`RouteGenerator.configureErrorHandling` 对全链 `onException(Exception.class)`；`maximumRedeliveries = job.retryCount`、`redeliveryDelay = job.backoffMs`；最终失败记一条 `FAILED` 消息步并把运行标为 `FAILED`。
- **handled 策略**：MQ 消费主路由 `handled=true`（失败后消费侧不重投队列）；重放/轮询/断点路由 `handled=false`（异常抛回调用方）。
- **消息历史（ADR-0009）**：死信升维为「运行 + 消息步」。
  - `job_run`：一次执行（id/jobId/status/input_snapshot/error/started_at/finished_at），`status ∈ RUNNING/SUCCESS/FAILED`；
  - `message_step`：一条消息 × 一个动作（runId/messageId/step/actionName/status/data_snapshot/input_snapshot/error/created_at/attempt/businessKey），`status ∈ SUCCESS/FAILED/REDELIVERED`（RETRYING/SUPPRESSED 预留）；按 `(run_id, message_id, step)` 唯一。
- **业务键**：源动作新增 `businessKey`（READ_SQL=源列；MQ_CONSUME/任务输入=JSON 路径），在**消息创建时**提取；可为空（不分组/去重）。
- **断点续跑**：从一条 `FAILED` 消息步精确重投（用该步数据从该动作重放，不整链重跑），成功后原步状态 `REDELIVERED`。
- **列表分页**：`GET /api/history/runs` 与 `GET /api/history/steps`（默认 FAILED，`page/size`，size≤100）返回 `{total, records}`；步骤可按 jobId/runId/messageId/businessKey/status 过滤。
- **代码位置**：`service/MessageHistoryService.java`（核心）、`web/HistoryController.java`、`service/RouteGenerator.java`（路由内记录/失败/续跑）、`model/JobRun.java`、`model/MessageStep.java`、`spike/db/init.sql`、`schema.sql`。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-18 | 失败即停 + 重试退避 + 死信记录（不承诺分布式补偿） | 跨 HTTP/JDBC/MQ 无法可靠补偿；跳过失败不可对账 | — | ADR-0003 |
| 08-26 | 死信列表分页 + 默认过滤 FAILED | 数据量大全量返回卡顿 | — | 本轮 |
| 08-26 | 死信输入存真实 JSON（修复 StreamCache 存成 Java toString） | 显示与重投都依赖合法 JSON | — | 本轮 |
| 09-01 | 死信升维为消息历史（job_run/message_step + 断点续跑 + 业务键） | 死信无时间戳/无运行归属/无消息步，无法精确断点与按业务追溯 | — | ADR-0009, #70 |

## 当前生效

- 失败即停 + 重试退避 + 消息历史（ADR-0003 / ADR-0009）
- 历史页默认 FAILED，按运行/消息/业务键分组检索
- 从失败消息步断点续跑（人工重投），成功后原步 `REDELIVERED`
- 源动作 `businessKey` 配置与消息创建时提取
- 旧 `dead_letter` 已弃用（不迁移）；`DeadLetter*` 代码与页面、`dead_letter` 表移除

## 待办 / 已知限制

- 断点续跑对「下游 FIELD_MAP 引用被跳过步骤上下文」的场景不保证语义（常见写/发动作走 body，不受影响）；
- 存储随「消息数 × 步骤数」增长；
- 自动重试（RETRYING）、抑制/去重（SUPPRESSED）仅预留状态，未实现；
- 真实 MQ 话题做历史/重放、凭证明文加密（DS-3）不在本期范围。
