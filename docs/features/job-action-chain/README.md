# 任务与动作链

任务 = 一组按序号执行的**动作链**，可手动/自动调度/由外部触发；路由由动作链编译生成。

## 当前设计（现状）

- **任务模型**：`Job(id, siteId, name, status, direction, scheduleType, intervalValue, intervalUnit, scheduleTime, scheduleWeekday, timezone, retryCount, backoffMs, lastRunStatus/At/Error)`；水位已从任务移除（归 READ_SQL 动作）；`crontab` 列保留但仅承载系统生成的内部 cron（不对用户展示/编辑）；`lastRunStatus/At/Error` 由链路尾部 `finishRun` 统一回写（#88，见 `service/JobLastRunWriter.java`）。
- **方向（DS-9）**：`direction` ∈ NORTH/SOUTH，是**业务标签**；不做方向↔入口一致性校验，入口由链首动作种类决定。
- **动作种类（六种，DS-8）**：READ_SQL / WRITE_UPSERT / HTTP_CALL / MQ_CONSUME / MQ_PUBLISH / FIELD_MAP；`addition` 按类型强类型化（`ActionConfig` sealed interface）。
- **链约束**：MQ_CONSUME 只能链首（序号 1）；一条任务最多一个 MQ_CONSUME、最多一个带水位的 READ_SQL；动作序号唯一；跨步骤引用（`FIELD_MAP.sourceStep`）必须指向链中**存在且序号更早**的动作（#89，`ChainConsistencyValidator`：写时 400 / `/validate` errors / 启用前拦截）。
- **路由生成（DS-8 片段组合）**：`RouteGenerator` 按动作种类生成 Camel 片段、按序号串联；三种入口：
  - `buildPollingEntry`（直连入口）：READ_SQL 链首 → 仅 `direct:job-<id>`；自动触发由 `JobScheduler` 依调度配置投递；
  - `buildConsumerEntry`：MQ_CONSUME 链首 → `spring-rabbitmq:<exchange>?exchangeType=<t>&queues=<queue>&routingKey=<rk>&autoDeclare=true` 消费（exchange 名/类型/binding key 归动作，缺省=数据源名/direct/queue，ADR-0010）；
  - `buildReplay`：`direct:job-<id>` 重放（手动执行/断点重放共用）。
- **上下文列表**：动作输出按序号存入 exchange 属性 `edge.context`，`FIELD_MAP.sourceStep` 指向来源步骤（0=任务输入）；MQ 消费入口把消息体放 `context[1]`。
- **生命周期**：任务启用时 `RouteRegistry` 注册路由、停用移除；启动后由 `RouteLifecycle`（`@Order(3)`）重载启用任务；**动作增/改/删后自动重建路由**（6d0895a）；启用/重建失败由 `RouteLifecycle` 自动停用 + 摘除路由/调度 + 写 `job.lastError`（启动重载只对 sourceStep 引用不一致这类配置问题停用，其余失败保留启用、只记日志）；启用/重建前先做 sourceStep 引用校验，不一致拒绝注册路由（#89）。
- **代码位置**：`model/Job.java`、`model/ScheduleConfig.java`（调度值类型：解析/校验/编译）、`model/Action.java`、`service/RouteGenerator.java`、`service/RouteRegistry.java`、`service/RouteLifecycle.java`（配置变更后的路由/调度重建与失败自动停用）、`service/JobScheduler.java`、`service/JobTrigger.java`（触发端点 adapter：`direct:job-<id>` 与 producer 调用）、`service/JobExecutionService.java`、`service/ActionService.java`。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | 任务/动作创建重设计（表单特化、水位移到读动作） | 旧表单不可用：站点数值框、addition 手填、水位位置错 | — | DS-7 / 66ae7d0 / 9737fe7 |
| 08-20 | 动作链通用化（六种 ActionKind + 上下文/sourceStep + 片段组合） | 固定南北模板无法插入“同步+通知”等组合 | — | DS-8 / action-chain-design |
| 08-21 | 方向显式声明（用户选北向/南向） | 入口推断会误判（MQ 消费也可北向） | 与 ADR-0002 语义澄清 | DS-9 / 67ee48c / a663aa1 |
| 08-24 | 动作变更自动重建路由 | 已运行路由不感知配置变化 | — | 6d0895a |
| 09-09 | `FIELD_MAP.sourceStep` 链一致性校验（写时 + `/validate` + 启用前） | 删中间动作/重排后 sourceStep 悬空或错位会让目标字段静默 null 且任务仍 SUCCESS | — | #89 / `ChainConsistencyValidator` |

## 待办 / 已知限制

- 序号调整（上移/下移）无专门交互，目前靠改序号；
- 链一致性只校验 `sourceStep` 的**存在性与先后**，不校验语义漂移（重排后同序号换成了另一个动作但仍在更早位置）；`MQ_PUBLISH.bodySourceStep` 的链级悬空仍靠运行时显式失败兜底（写时校验见 ed50f35）；
- 前端 `MappingEditor` 仍不联动改写已保存规则的 sourceStep（新增行取前一步）；任务列表的启用开关 `toggleStatus` 未捕获异常，启用被拦时只有接口 400 与 `job.lastError` 说明原因，界面不弹提示（用「校验配置」可看到 errors）；
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
