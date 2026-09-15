# ADR-0009: 从死信单据到消息历史（运行/消息步）

- 状态：已接受
- 日期：2026-09-01
- 实现：#70（消息历史：死信升维为运行/消息步）——已按本文落地
- 补充：#90（断点重投回填上游步骤快照 `edge.resumeContext`）——断点续跑对引用上游输出的 FIELD_MAP 同样可用；
  详见 `docs/flows/reliability-flow.md` ⑤ 与 `docs/features/reliability/README.md`
- 补充：#108（断点重投失败即失败）——`redeliver` 投递后检查返回 exchange 的异常，失败时抛"重投失败：<根因>"且
  **不改**原步状态：`REDELIVERED` 只表示"该步已成功续跑"，不再把失败的重投也标成它
- 补充：#109（`attempt` = 总尝试次数）——`message_step.attempt` 记该步的总投递次数（含 run 内自动重试），
  由 `onExceptionOccurred` 自计数；本文"预留 RETRYING"的语义后续同样依赖该计数

## 背景

现有死信只记录终端失败（`jobId + actionIndex + inputJson + error + status`），无时间戳、无"属于哪次运行"、
无该步上下文/数据，无法**精确断点续传**，也难以按业务键追溯或分步展现（C1 曾指出轮询链重投整链重跑）。

## 决策

把"死信"升维成**消息历史**：

- 新增 `job_run`（一次执行）与 `message_step`（一条消息 × 一个动作）两张核心记录；
- `message_step` 关键字段：`run_id`、`message_id`、`step`（动作序号）、`action_name`、`status`、
  `data_snapshot`（该步数据/上下文）、`input_snapshot`（原始输入）、`error`、`created_at`、`attempt`、断点信息；
- 状态机：`SUCCESS / FAILED / REDELIVERED` 必做；`RETRYING / SUPPRESSED` 预留；
  `REDELIVERED` 的准确含义是"该步已成功续跑"（#108：重投失败不得标它）；`attempt` 记**总尝试次数**（#109）；
- 死信页改为**历史页**，默认查询 `status=FAILED`，按运行/消息/业务键分组展示，提供"从此步断点续跑"；
- 业务键：源动作新增 `businessKey` 配置（READ_SQL=源列、MQ_CONSUME/任务输入=JSON 路径），在**消息创建时**提取，
  可为空（未配则不分组/去重）；
- 本期只做"**历史 + 精确断点 + 人工重投**"；自动重试（RETRYING）与抑制（SUPPRESSED）只预留状态，不实现。
- **旧 `dead_letter` 弃用**：现有死信历史数据无价值，**不做迁移**；`dead_letter` 表、`DeadLetter` 模型、
  `DeadLetterService`/`DeadLetterController`、死信页与路由里的死信记录逻辑，统一由消息历史（`job_run`/`message_step`
  + 历史页）**取代**（或移除相关代码）。

## Considered Options

- 粒度：Q3-A 消息步细粒度（选定） vs 消息级一行的 JSON 步骤序列（B，断点/分步需反解析）。
- 身份/分组：`run_id + message_id + business_key`（选定）；`business_key` 需源动作配置（A） vs 事后从写动作
  `idempotencyKey` 尽力补（B，跨步/跨运行分组弱）。
- 存量：**弃用旧 `dead_letter`**（无价值、不迁移，`message_step` 从新数据开始）（选定） vs 一次性升格 vs 保留旧表只读。
- 范围：本期不做自动重试，仅预留状态。

## Consequences

- 无存量迁移负担：旧 `dead_letter` 数据直接弃用；但同时要**移除或替换**现有 `dead_letter` 相关代码/表/页面/死信记录逻辑。
- 存储随"消息数 × 步骤数"增长，历史表体量上升。
- 源动作配置模型 + 表单 + 校验 + 迁移都多一个 `businessKey`。
- "失败即停 + 人工重投"语义保留；自动重试/抑制为后续增强。
