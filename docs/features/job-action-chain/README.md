# 任务与动作链

任务 = 一组按序号执行的**动作链**，可手动/自动调度/由外部触发；路由由动作链编译生成。

## 当前设计（现状）

- **任务模型**：`Job(id, siteId, name, status, direction, scheduleType, intervalValue, intervalUnit, scheduleTime, scheduleWeekday, timezone, retryCount, backoffMs, lastRunStatus/At/Error)`；水位已从任务移除（归 READ_SQL 动作）；`crontab` 列保留但仅承载系统生成的内部 cron（不对用户展示/编辑）。
- **方向（DS-9）**：`direction` ∈ NORTH/SOUTH，是**业务标签**；不做方向↔入口一致性校验，入口由链首动作种类决定。
- **动作种类（六种，DS-8）**：READ_SQL / WRITE_UPSERT / HTTP_CALL / MQ_CONSUME / MQ_PUBLISH / FIELD_MAP；`addition` 按类型强类型化（`ActionConfig` sealed interface）。
- **链约束**：MQ_CONSUME 只能链首（序号 1）；一条任务最多一个 MQ_CONSUME、最多一个带水位的 READ_SQL；动作序号唯一。
- **路由生成（DS-8 片段组合）**：`RouteGenerator` 按动作种类生成 Camel 片段、按序号串联；三种入口：
  - `buildPollingEntry`（直连入口）：READ_SQL 链首 → 仅 `direct:job-<id>`；自动触发由 `JobScheduler` 依调度配置投递；
  - `buildConsumerEntry`：MQ_CONSUME 链首 → `spring-rabbitmq:<数据源名>?queues=<queue>&routingKey=<queue>&autoDeclare=true` 消费；
  - `buildReplay`：`direct:job-<id>` 重放（手动执行/断点重放共用）。
- **上下文列表**：动作输出按序号存入 exchange 属性 `edge.context`，`FIELD_MAP.sourceStep` 指向来源步骤（0=任务输入）；MQ 消费入口把消息体放 `context[1]`。
- **生命周期**：任务启用时 `RouteRegistry` 注册路由、停用移除、启动自动重载；**动作增/改/删后自动重建路由**（6d0895a），重建失败自动停用任务。
- **代码位置**：`model/Job.java`、`model/Action.java`、`service/RouteGenerator.java`、`service/RouteRegistry.java`、`service/ActionService.java`、`service/JobExecutionService.java`。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | 任务/动作创建重设计（表单特化、水位移到读动作） | 旧表单不可用：站点数值框、addition 手填、水位位置错 | — | DS-7 / 66ae7d0 / 9737fe7 |
| 08-20 | 动作链通用化（六种 ActionKind + 上下文/sourceStep + 片段组合） | 固定南北模板无法插入“同步+通知”等组合 | — | DS-8 / action-chain-design |
| 08-21 | 方向显式声明（用户选北向/南向） | 入口推断会误判（MQ 消费也可北向） | 与 ADR-0002 语义澄清 | DS-9 / 67ee48c / a663aa1 |
| 08-24 | 动作变更自动重建路由 | 已运行路由不感知配置变化 | — | 6d0895a |

## 待办 / 已知限制

- 序号调整（上移/下移）无专门交互，目前靠改序号；
- 自动调度触发由 `JobScheduler`（Spring `TaskScheduler`）依调度配置（不自动/间隔/每天/每周）注册，投递 `direct:job-<id>`，并结合 `timezone`；MQ_CONSUME 链首不设定时。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | 任务/动作创建重设计（表单特化、水位移到读动作） | 旧表单不可用：站点数值框、addition 手填、水位位置错 | — | DS-7 / 66ae7d0 / 9737fe7 |
| 08-20 | 动作链通用化（六种 ActionKind + 上下文/sourceStep + 片段组合） | 固定南北模板无法插入“同步+通知”等组合 | — | DS-8 / action-chain-design |
| 08-21 | 方向显式声明（用户选北向/南向） | 入口推断会误判（MQ 消费也可北向） | 与 ADR-0002 语义澄清 | DS-9 / 67ee48c / a663aa1 |
| 08-24 | 动作变更自动重建路由 | 已运行路由不感知配置变化 | — | 6d0895a |

## 当前生效

- 六种 ActionKind、方向显式声明、动作变更自动重建路由
- 设计细节：docs/action-chain-design.md（DS-8 细化稿）
