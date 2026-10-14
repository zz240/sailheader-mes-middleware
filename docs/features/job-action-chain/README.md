# 任务与动作链

任务 = 一组按序号执行的**动作链**，可手动/自动调度/由外部触发；路由由动作链编译生成。

## 当前设计（现状）

- **任务模型**：`Job(id, siteId, name, status, direction, scheduleType, intervalValue, intervalUnit, scheduleTime, scheduleWeekday, timezone, retryCount, backoffMs, lastRunStatus/At/Error)`；水位已从任务移除（归 READ_SQL 动作）；`crontab` 列保留但仅承载系统生成的内部 cron（不对用户展示/编辑）；`lastRunStatus/At/Error` 由链路尾部 `finishRun` 统一回写（#88，见 `service/JobLastRunWriter.java`）。
- **方向（DS-9）**：`direction` ∈ NORTH/SOUTH，是**业务标签**；不做方向↔入口一致性校验，入口由链首动作种类决定。
- **动作种类（八种，DS-8）**：READ_SQL / WRITE_UPSERT / HTTP_CALL / MQ_CONSUME / MQ_PUBLISH / FIELD_MAP / **ONE_TO_MANY（一分多，两档：拆分 `mode=split` / 还原 `mode=restore`）** / **AGGREGATE（合并，#142）**；`addition` 按类型强类型化（`ActionConfig` sealed interface，各自一个 `config/*Config.java`）。**拆分档**是**基数变化**动作（1 → N）：把来源里的一片数组摊成 N 条子消息，此后每个动作逐条执行；它**自己记一行消息步**（输入是父消息、输出是子消息们），子消息带父消息标识（谱系）、批内序号与现生成的批号（批次，ADR-0017）。来源三格：`sourceStep`（空 = 当前消息体）、`sourcePath`（空 = 来源本身就是数组）、`elementKey`（必填，子消息的业务键）。AGGREGATE 是另一个基数变化动作（N → 1）：攒住走到它这里的**一批**消息（攒齐的边界 = 上游读取批读完那一刻，**不做条数阈值与超时**），**打成一条报文**——配置只有一格 `bodyField`（整批包成 `{"<bodyField>": [ … ]}`）。**它不认识出口**：发去哪儿由链上后面的 `HTTP_CALL` / `MQ_PUBLISH` 决定（打包完链上只有一条消息，出站动作因此天然只发一次）；合并那一行记的是「整批 → 打包报文」，出站与还原各按**消息粒度**记行——**不再有**承载整份请求与响应的"摘要行"（它为"单独重发某一批"服务，那条路径已由 #147 删除）。**还原档**把出站拿回来的响应按配对字段摊回**原来那 N 条消息**（1 → N）：配置 `sourceStep`（必填，响应在哪个动作的输出里）、`sourcePath`（可空）、`resultKeyField`、`attachAs`；它**沿用**那些消息的标识 / 业务键 / 父消息标识 / 批内序号，**也不换批号**（这是它与拆分档的区别：拆分新造消息、换号；还原回到原处、不换号）。两档各认各的键（别档的键报「未知字段」），落库 JSON 只多一格 `mode`。
- **链约束**：MQ_CONSUME 只能链首（序号 1）；一条任务最多一个 MQ_CONSUME、最多一个带水位的 READ_SQL；动作序号唯一；跨步骤引用（`FIELD_MAP.sourceStep`、`ONE_TO_MANY.sourceStep`——拆分/还原两档都走这条）必须指向链中**存在且序号更早**的动作（#89 / #140 / #142 / #143，`ChainConsistencyValidator`：写时 400 / `/validate` errors / 启用前拦截）；**还原档还要求链上有上游合并动作**（它靠成员清单回到原来那几条消息）；AGGREGATE 没有源步骤可指（它攒的是走到它这里的那批消息），链级只校验它的配置能不能解析。**多目标**（同一批发两个终端）= 链上两个出站动作，顺序执行、**失败即停**；失败后**重跑任务**会从未完成的那一批整批重走（水位未推进），目标端会再收到一次请求。**不做跨系统原子性**：已经发出去的目标撤不回来，仍是「至少一次 + 目标端按业务键幂等」（ADR-0003；#147 已删除「重发这一批」这条只发请求、不跑下游的路径）。
- **路由生成（DS-8 片段组合）**：`RouteGenerator` **只做装配**（#145 把它从 1405 行拆到 400 行）：判定入口、按序号把片段串成一条路由、按开过的层数收尾。三种入口：
  - `buildPollingEntry`（直连入口）：READ_SQL 链首 → 仅 `direct:job-<id>`；自动触发由 `JobScheduler` 依调度配置投递；
  - `buildConsumerEntry`：MQ_CONSUME 链首 → `spring-rabbitmq:<exchange>?exchangeType=<t>&queues=<queue>&routingKey=<rk>&autoDeclare=true` 消费（exchange 名/类型/binding key 归动作，缺省=数据源名/direct/queue，ADR-0010）；
  - `buildReplay`：`direct:job-<id>` 重放（手动执行/断点重放共用）。
- **上下文列表**：动作输出按序号存入 exchange 属性 `edge.context`，`FIELD_MAP.sourceStep` 指向来源步骤（0=任务输入）；MQ 消费入口把消息体放 `context[1]`。`HTTP_CALL` 也不例外（#123）：出站后把**响应**解析进 `context[本动作序号]`（对象→Map / 数组→List / 非 JSON→原样字符串 / 空体→null），并把消息体**还原为本次请求体**（#126：以前响应会顶替 body，紧随的 `WRITE_UPSERT` 在空响应上直接 NPE）；想继续往下用响应就显式引用该动作序号。捕获内容装不下快照列时该动作显式失败，不写半截（列是 `MEDIUMTEXT`、单条上限 16 MB，#141，见 ADR-0015）。
- **生命周期**：任务启用时 `RouteRegistry` 注册路由、停用移除；启动后由 `RouteLifecycle`（`@Order(3)`）重载启用任务；**动作增/改/删后自动重建路由**（6d0895a）；启用/重建失败由 `RouteLifecycle` 自动停用 + 摘除路由/调度 + 写 `job.lastError`（启动重载只对 sourceStep 引用不一致这类配置问题停用，其余失败保留启用、只记日志）；启用/重建前先做 sourceStep 引用校验，不一致拒绝注册路由（#89）。
- **代码位置**：`model/Job.java`、`model/ScheduleConfig.java`（调度值类型：解析/校验/编译）、`model/Action.java`、`service/RouteGenerator.java`（**只做装配**：`build` / 三种入口 + 断点续跑路由 / `appendChain` 的层数记账）、`service/ActionFragment.java`（一种动作一段片段）、`service/SplitFragment.java`（`appendSplit` / `resolveSplitSource` / `splitArray` / `beginChildMessage` 是拆分那几段）、`service/AggregateFragment.java`（`appendAggregateCollect` / `appendAggregatePack` / `takeBatch` 是合并那几段，攒批表也只归它）、`service/RestoreFragment.java`（`appendRestore` / `restoreCarrier` 是还原那几段；其中重发收尾 `settleBatchResend` 与 `markBatchCarrier` 随 #147 一并删除）、`service/StepLedger.java`（消息步 / 运行簿记：计时、尝试次数、记行、上下文）、`service/ReadWriteFragments.java`（读库分批 / 幂等写 / 响应捕获 / 连接池与方言）、`service/RouteKeys.java`（`edge.*` 运行期属性名，三边共用的契约）、`service/ErrorHandling.java`（`onException` 失败面）、`service/ElementMessageFactory.java`（元素 → 已初始化消息、点分路径取值）、`service/AggregateBatch.java`（一批的成员清单与配对，纯逻辑）、`service/RouteRegistry.java`、`service/RouteLifecycle.java`（配置变更后的路由/调度重建与失败自动停用）、`service/JobScheduler.java`、`service/JobTrigger.java`（触发端点 adapter：`direct:job-<id>` 与 producer 调用）、`service/JobExecutionService.java`、`service/ActionService.java`。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | 任务/动作创建重设计（表单特化、水位移到读动作） | 旧表单不可用：站点数值框、addition 手填、水位位置错 | — | DS-7 / 66ae7d0 / 9737fe7 |
| 08-20 | 动作链通用化（六种 ActionKind + 上下文/sourceStep + 片段组合） | 固定南北模板无法插入“同步+通知”等组合 | — | DS-8 / action-chain-design |
| 08-21 | 方向显式声明（用户选北向/南向） | 入口推断会误判（MQ 消费也可北向） | 与 ADR-0002 语义澄清 | DS-9 / 67ee48c / a663aa1 |
| 08-24 | 动作变更自动重建路由 | 已运行路由不感知配置变化 | — | 6d0895a |
| 09-09 | `FIELD_MAP.sourceStep` 链一致性校验（写时 + `/validate` + 启用前） | 删中间动作/重排后 sourceStep 悬空或错位会让目标字段静默 null 且任务仍 SUCCESS | — | #89 / `ChainConsistencyValidator` |
| 09-15 | 字段映射动作的名称 / 序号改从全屏编辑器那条路保存 | 旧编辑器删掉之后（#107），弹窗里没有可编辑的映射，不带映射的 PUT 会被「至少一条映射规则」拒掉，改名就没人能存了 | 弹窗「保存」带上编辑器里那份映射（按钮改叫「改名称 / 序号顺便保存映射」） | #107 / `JobsView.vue` |
| 09-20 | `HTTP_CALL` 响应进 `context[本动作序号]`，消息体还原为请求体 | 契约本就要求每个动作写 `context`，HTTP 动作却只出站：下游 `FIELD_MAP` 取不到响应字段（B6 的 `FHandle` 回写做不了）；且响应顶替了消息体，空响应时紧随的 `WRITE_UPSERT` 必 NPE（#126） | — | #123 / #126 / ADR-0015 |
| 09-28 | 拆分动作（七种 ActionKind）：一份报文里的数组摊成 N 条子消息 | 产品只处理 1:1 数据流，「头行 + 子件 N 行」与「HTTP 响应里的明细」接不了（`READ_SQL` 是唯一会分裂的东西，且只认一批源行） | — | #140 / ADR-0017 / `config/action/OneToManyConfig.java` |
| 10-06 | 合并动作（八种 ActionKind）：攒住一批只发一次，响应按业务键还原 | 1000 条码就是 1000 次请求；对方的批量接口被打爆，且「这批发了什么」在历史里答不出来 | 攒齐的边界绑上游读取批（不做条数阈值与超时）；不用 Camel 的 `aggregate` EIP（实测它的攒齐后续是异步的，会让水位与失败判定错位） | #142 / ADR-0017 / `config/action/AggregateConfig.java` |

## 待办 / 已知限制

- 序号调整（上移/下移）无专门交互，目前靠改序号；
- 链一致性只校验 `sourceStep` 的**存在性与先后**，不校验语义漂移（重排后同序号换成了另一个动作但仍在更早位置）；`MQ_PUBLISH.bodySourceStep` 的链级悬空仍靠运行时显式失败兜底（写时校验见 ed50f35）；
- 前端映射编辑器（`FieldMappingWorkbench.vue`）仍不联动改写已保存规则的 sourceStep（新增行取前一步）；任务列表的启用开关 `toggleStatus` 未捕获异常，启用被拦时只有接口 400 与 `job.lastError` 说明原因，界面不弹提示（编辑器里的「校验配置」可看到 errors）；
- 自动调度触发由 `JobScheduler`（Spring `TaskScheduler`）依调度配置（不自动/间隔/每天/每周）注册，投递 `direct:job-<id>`，并结合 `timezone`；MQ_CONSUME 链首不设定时。

## 当前生效

- 八种 ActionKind（含一分多的两档与合并）、方向显式声明、动作变更自动重建路由
- 设计细节：docs/action-chain-design.md（DS-8 细化稿；§1.1 = 基数变化动作怎么串、链一致性校验覆盖什么）
- 实施视角的整条配法：docs/user-guide.md 的「批量接口怎么做」
- 刻意口径（超时只当失败判据 / 合并之前的单条重投立刻发 / 容量闸显式失败不写半截）**不是 bug**，清单见 docs/action-chain-design.md 末节
- 决策基线：docs/adr/0017-cardinality-change-and-message-history.md（文末有 #142 / #143 两节落地记录；决策 6 的摘要行与决策 7 的整批重发已由 [#147](https://github.com/zz240/sailheader-mes-middleware/issues/147) 作废，该 ADR 只追加状态、正文未改）
- 失败恢复：**重跑任务**（水位未推进即整批重走，含下游）；合并之前的单条仍走断点重投（#147）
