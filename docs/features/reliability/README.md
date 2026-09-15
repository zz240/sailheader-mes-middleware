# 可靠性（失败/重试/消息历史/断点重投）

失败即停 + 按任务配置重试退避 + 消息历史记录与断点重投；不承诺跨系统补偿事务。

## 当前设计（现状）

- **失败即停（ADR-0003）**：`RouteGenerator.configureErrorHandling` 对全链 `onException(Exception.class)`；`maximumRedeliveries = job.retryCount`、`redeliveryDelay = job.backoffMs`；最终失败记一条 `FAILED` 消息步并把运行标为 `FAILED`。
  - **总尝试次数（#109）**：`attempt` = 该步的总投递次数，由 `onExceptionOccurred` 自计数（exchange property `edge.failedAttempts`，与所属动作序号 `edge.failedAttemptsStep` 成对读写，读的时候对上号才用——否则前一步骤的重试会漏给后面的步骤）：失败步 = 该步失败次数（末次失败也走异常回调），成功步 = 该步失败次数 + 1。`retryCount=2` 必失败 → 3；`retryCount=0` → 1；重试一次后成功 → 2；前一步骤重试过后再有一次成功的步骤仍是 1。
- **handled 策略**：MQ 消费主路由 `handled=true`（失败后消费侧不重投队列）；重放/轮询/断点路由 `handled=false`（异常抛回调用方）。
- **消息历史（ADR-0009）**：死信升维为「运行 + 消息步」。
  - `job_run`：一次执行（id/jobId/status/input_snapshot/error/started_at/finished_at），`status ∈ RUNNING/SUCCESS/FAILED`；
  - `message_step`：一条消息 × 一个动作（runId/messageId/step/actionName/status/data_snapshot/input_snapshot/error/created_at/attempt/businessKey），`status ∈ SUCCESS/FAILED/REDELIVERED`（RETRYING/SUPPRESSED 预留）；按 `(run_id, message_id, step)` 唯一。
- **业务键**：源动作新增 `businessKey`（READ_SQL=源列；MQ_CONSUME/任务输入=JSON 路径），在**消息创建时**提取；可为空（不分组/去重）。
- **任务最近运行结果（#88）**：每次运行结束（成功/失败/重投）由 `MessageHistoryService.finishRun` 经 `JobLastRunWriter` 统一回写 `job.last_run_status/last_run_at/last_error`（错误取 `job_run.error`；成功清空 `last_error`），覆盖 MQ 消费/自动调度/手动执行/断点重投；手动执行与外部触发只回读任务返回。
- **断点续跑**：从一条 `FAILED` 消息步精确重投（用该步数据从该动作重放，不整链重跑），成功后原步状态 `REDELIVERED`。
  - **上游快照回填（#90）**：重投时 `MessageHistoryService.redeliver` 按 `(runId, messageId)` 取 `step < resumeFrom` 且 `SUCCESS` 的消息步，把各自 `data_snapshot` 解析后经 property `edge.resumeContext` 随 `direct:job-<id>-resume` 投递；断点路由入口回填 `context[step]`。
  - 于是被跳过的上游步骤仍可被下游规则引用（如 FIELD_MAP 的 `sourceStep=1`），不再因 `$s_1` 未定义而**静默映射出空**（这是以前写库/推送型链之外的主要缺口）。
  - 边界：上游步骤无快照（源行未记 `data_snapshot`、失败步、被跳步）→ **该步骤不进上下文**（属性照常投递，值为空 `Map`），下游引用仍是空，行为与现状一致；`context[0]` 语义保持不变（= 本次重投 body，即该步 `inputSnapshot`）。
  - **失败就报失败（#108）**：`redeliver` 投递后检查返回 exchange（`getException()` / `EXCEPTION_CAUGHT` / `isFailed()`），失败即抛"重投失败：<根因>"且**不改**原步状态。`ProducerTemplate.request(uri, processor)` 不重抛 exchange 上的异常，少了这道检查就会出现"接口返回 OK + 原步 `REDELIVERED`，而新 run 是 `FAILED`"（实测 run 301/302）。
- **列表分页**：`GET /api/history/runs`（运行）、`GET /api/history/steps` 与扁平别名 `GET /api/history`（消息步，默认 FAILED，`page/size`，size≤100）返回 `{total, records}`；步骤可按 jobId/runId/messageId/businessKey/status 过滤；`GET /api/history/{id}` 与 `/steps/{id}` 返回某消息步完整字段。
- **交付语义（至少一次）**：水位仅整批成功才推进，失败整批重读（at-least-once）；重试退避与断点重投下 HTTP/MQ 推送**可能重复**——南向 `WRITE_UPSERT` 按幂等键去重，北向推送需 MES 按业务键去重。与 ADR-0003 一致（不承诺恰好一次/分布式补偿）。
- **代码位置**：`service/MessageHistoryService.java`（核心）、`service/JobLastRunWriter.java`（任务最近运行结果回写）、`web/HistoryController.java`、`service/RouteGenerator.java`（路由内记录/失败/续跑）、`model/JobRun.java`、`model/MessageStep.java`、`spike/src/main/resources/db/migration/`（表定义）、`spike/src/test/resources/schema.sql`（H2 测试库）。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-18 | 失败即停 + 重试退避 + 死信记录（不承诺分布式补偿） | 跨 HTTP/JDBC/MQ 无法可靠补偿；跳过失败不可对账 | — | ADR-0003 |
| 08-26 | 死信列表分页 + 默认过滤 FAILED | 数据量大全量返回卡顿 | — | 本轮 |
| 08-26 | 死信输入存真实 JSON（修复 StreamCache 存成 Java toString） | 显示与重投都依赖合法 JSON | — | 本轮 |
| 09-01 | 死信升维为消息历史（job_run/message_step + 断点续跑 + 业务键） | 死信无时间戳/无运行归属/无消息步，无法精确断点与按业务追溯 | — | ADR-0009, #70 |
| 09-08 | 任务最近运行结果统一回写（finishRun 一处写入） | 消费/调度链路不回写，任务列表状态与真实执行不一致 | — | #88 |
| 09-10 | 断点重投回填上游步骤快照（`edge.resumeContext`） | 被跳步骤不写 context，而下游 FIELD_MAP 唯一输入是整份 context → 引用上游输出的映射重投后静默出空 | — | #90 |
| 09-15 | 重投失败不再被当成成功（检查返回 exchange 的异常） | `request(uri, processor)` 不重抛 exchange 异常 → 接口返回 OK、原步标 `REDELIVERED`，而新 run 是 `FAILED`，运维据此收工 | — | #108 |
| 09-15 | `attempt` 修为真实总尝试次数（`onExceptionOccurred` 自计数） | 原读 exchange property `CamelRedeliveryCounter`，而 Camel 4 把它写进 message header → 恒为 `null + 1`，历史页「尝试次数」永远显示 1 | 保留 run 内自动重投的决策不变 | #109 |

## 当前生效

- 失败即停 + 重试退避 + 消息历史（ADR-0003 / ADR-0009）
- 历史页默认 FAILED，按运行/消息/业务键分组检索
- 从失败消息步断点续跑（人工重投），成功后原步 `REDELIVERED`；重投失败则抛"重投失败：<根因>"且原步保持 `FAILED`（#108）；重投回填上游步骤快照，引用上游输出的映射链同样可续跑（#90）
- 消息步 `attempt` 记录**总尝试次数**（含 run 内自动重投），历史页「尝试次数」可直接回答"一次就成功还是重试才成功"（#109）
- 源动作 `businessKey` 配置与消息创建时提取
- 任务最近运行结果由 `finishRun` 统一回写（消费/调度/手动/重投），手动与外部触发只回读
- 旧 `dead_letter` 已弃用（不迁移）；`DeadLetter*` 代码与页面、`dead_letter` 表移除

## 待办 / 已知限制

- 断点续跑的 `edge.resumeContext` 快照以内存 property 传递，大 payload 会放大单次 exchange（不落盘，可接受）；仅在字段映射规则实际引用上游步骤时才有必要，按需注入可作为后续优化；
- 存储随「消息数 × 步骤数」增长；
- 任务行回写随运行数增长（消费型任务每条消息更新一次 `job`），高吞吐场景为已知写放大；并发运行下 `last_run_*` 取最后写入者（"最近"语义尽力而为）；
- 自动重试（RETRYING）、抑制/去重（SUPPRESSED）仅预留状态，未实现；
- 真实 MQ 话题做历史/重放、凭证明文加密（DS-3）不在本期范围。
