# 动作链通用化设计（DS-8 细化稿）

> 状态：已确认（2026-08-19）。§1 的动作种类表、§1.1、§2、§3 与 §9 已随 #140（拆分）与 #142（合并 / 还原）补记（2026-10-07）。关联：DS-2（typed addition）、DS-6（数据源连接级）、DS-7（动作表单重设计）。
> 目标：把 RouteGenerator 从"两个固定模板"改为"动作片段组合"，使"同步的同时发消息"等真实场景可配置。

## 1. 动作语义化：动作种类目录

把 `ActionType`（REMOTE_ACCESS / FIELD_MAPPING）升级为显式语义的动作种类，每个种类有自己的配置 record 与校验（复用 sealed `ActionConfig` 模式）：

| 动作种类 | 语义 | 在路由中的位置 | 关键参数（typed addition） |
|---|---|---|---|
| `MQ_CONSUME` | 南向入口：从队列消费 | 只能作链首 | 数据源（broker）+ queue + exchange/exchangeType（direct/topic/fanout/headers，缺省=数据源名 + direct，ADR-0010）+ routingKey（=binding key，topic 模式串；fanout 由 broker 忽略，可不填；缺省=queue） |
| `READ_SQL` | 从 MySQL 增量/刷量读（keyset 分批） | 链中部（北向通常首位） | 数据源 + SQL + 水位策略 + 水位列 + 批大小 |
| `FIELD_MAP` | 字段映射 | 任意 | rules（sourceStep + sourcePath + target + transform） |
| `WRITE_UPSERT` | MySQL 幂等 upsert | 任意 | 数据源 + tableName + idempotencyKey |
| `HTTP_CALL` | 调 HTTP 接口 | 任意 | 数据源（根地址）+ path + method + 鉴权 |
| `MQ_PUBLISH` | 发 MQ 消息 | 任意 | 数据源（broker）+ queue/exchange + routingKey + bodySourceStep（必填） |
| `ONE_TO_MANY` | 一分多（1 → N，基数变化），两档：**拆分**（`mode=split`：造 N 条**新**消息）/ **还原**（`mode=restore`：把出站响应还回**原来那几条**消息） | 任意（还原档通常紧跟出站动作） | 公共：`mode`（必填）+ `sourceStep` + `sourcePath`；拆分档另认 `elementKey`（必填：子消息的业务键取自元素里的哪个字段）+ `keepParent`（缺省 true：外层字段是否摊平进子消息）；还原档另认 `resultKeyField`（必填：响应每一项里跟业务键比的字段）+ `attachAs`（必填：配到的那一份写进各自 body 的哪个键）。两档各认各的键，别档的键是「未知字段」；还原档要求上游链上有 `AGGREGATE`（成员清单是它与出站之间唯一的联系） |
| `AGGREGATE` | 合并：攒住一批消息打成一条报文（N → 1，基数变化） | 任意（打包之后才有出站） | bodyField（必填：整批包成 `{"<bodyField>": […每条消息自己那一片…]}`）。**它不认识出口**——数据源 / 路径 / 方法都不在它这儿 |

设计要点：

- 读写意图**显式化**，不再靠"位置 + addition 内容"猜测；
- `MQ_CONSUME` 只能是链首（路由入口），其余动作可在任意位置；MVP 限制一条链最多一个 `MQ_CONSUME` 和一个带水位的 `READ_SQL`（校验期拦截）；
- 数据源引用全部是"连接级"（DS-6 方案 A）：HTTP 数据源存根地址、路径在动作里；MQ 数据源存 broker、队列在动作里。

### 1.1 基数变化动作（#140 / #142 / #143 中版）怎么串、链一致性校验覆盖什么

基数变化动作（**一分多的两档** + **合并**）合起来表达「批量 / 数组型」接口，链上最常见的形状：

```text
读库（这一批 N 行）─ 1→N(拆分) ─ N 条 ─ AGGREGATE ─ 1 条（整批报文）─ HTTP_CALL / MQ_PUBLISH ─ 1→N(还原) ─ N 条 ─ WRITE_UPSERT
```

- **批号在基数变化动作上转手**：一分多的**拆分档**现生成一个新号发给子消息；AGGREGATE 再转一次手
  （打包出来那一条带新号）；**还原档不换号**——它是把这一批还原回成员，不是新的一批
  （口径见 `CONTEXT.md`「批次」与 ADR-0017 决策 1–3）。
- **摘要行归出站动作**：`batch:<批号>` 那一行（整份请求 + 整份响应）不在合并动作上，而在出站那一步（ADR-0017 决策 6）。
  合并**不认识出口**：发去哪儿由链上后面的 `HTTP_CALL` / `MQ_PUBLISH` 决定；多目标 = 多个出站动作（顺序执行、失败即停）。
- **片段按层数记账**：读库那层是 `loopDoWhile`，一分多的两档（拆分 / 还原）各开一层 `split`；每开一层收尾要 `end()` 一次、
  且**由内到外**（先关拆分、再交水位、最后关循环）。Camel 的 `end()` 在循环体内只往外退一层，
  嵌套时只退一次会拿到外层那个 split（#140 实测踩到，路由直接建不起来）。
- **「承运单」是代码里那张单**（`AggregateBatch.Carrier`）：一条消息在合并那一步的全部现场——我是谁 /
  我这一片是什么 / 上下文到哪儿了；这一批的清单挂在属性 `edge.batchManifest` 上随链走，**不进报文、不进快照**。
- **链一致性校验覆盖什么**（`ChainConsistencyValidator`；写时 400 / `/validate` errors / 启用前拦截三处共用同一份判据）：
  ① `MQ_CONSUME` 只能作链首，一条链最多一个；② 一条链最多一个带水位的 `READ_SQL`；③ 动作序号唯一；
  ④ 跨步骤引用（`FIELD_MAP.sourceStep`、`ONE_TO_MANY.sourceStep`——两档都走这条）必须指向链中**存在且序号更早**的动作；
  ⑤ `MQ_PUBLISH.bodySourceStep` 用同一份判据（只是错误文案里的字段名不同）；
  ⑥ **还原档要求链上有上游合并动作**（它靠成员清单回到原来那几条消息）。
  AGGREGATE **没有源步骤可指**（它攒的是走到它这里的那批消息），链级只校验它的配置能不能解析；
  重排后「同序号换成了另一个动作」这种语义漂移不校验（#89 的已知限制）。

## 2. 上下文列表

`Exchange` property `edge.context`：`Map<Integer, Object>`，键 = 动作序号（action_index），`0` 为任务输入（Job Input）。

- 每个动作完成后写入 `context[actionIndex] = 输出`；HTTP 动作的输出就是**解析后的响应**
  （JSON 对象 → `Map`、数组 → `List`、非 JSON → 原样字符串、空响应体 → `null`），
  且出站后链上消息体**保持为本次请求体**（#123 / #126）——想继续往下传响应，就显式引用该动作序号；
  捕获内容不截断：装不下 `message_step` 快照列时该动作**显式失败**（列容量跟着列宽走 = `MEDIUMTEXT` 16 MB，见 ADR-0015 / #141）；
- `READ_SQL` 的 split 后，每个子 exchange 的 `context[readIndex]` = 该行数据；
- **拆分档**（`ONE_TO_MANY`，`mode=split`）的 split 后，每个子 exchange 的 `context[splitIndex]` = **自己那个元素**（拆之前是整片数组）；
  子消息各有自己的消息标识与业务键，并带父消息标识（谱系）、批内序号与批号（批次，见 ADR-0017）。
  来源解析不出来（步骤被跳过 / 路径断了 / 取到的不是数组）时该步**显式失败**，不静默拆出 0 条；
- `AGGREGATE`（#142）打包之后，链上只剩**一条消息**：`context[aggregateIndex] = 打包出来的报文`；
  它这一行的输入是**整批**（每条消息各自那一片，进输入快照）。空批 / 整批被断点续投跳过时不打包、也不发；
- **还原档**（`mode=restore`）之后，这一批每条消息的 `context[还原序号] = 自己配对到的那一份`（摊回之前整批共用那一份响应）；
  消息身份与批号都沿用合并那一批，响应里有一条配不上就整批失败（同成同败）；
- 字段映射规则引用改为结构化 `sourceStep + sourcePath`（不再拼接路径字符串），`sourceStep` 默认等于"前一个动作的序号"（迁移时按原链推断）；
- `MQ_PUBLISH` 的请求体来源 = `bodySourceStep`（**必填**，显式指向链中存在的更早动作，校验期检查；发布时该步无输出——如被断点跳过——则执行失败，不静默发旧值）；`HTTP_CALL` 直接使用当前 body（上一步输出）；
- 重排/删除动作后，依赖规则的 `sourceStep` 由**校验期报警**兜底（#89）：写时（`ActionService` 新增/修改 FIELD_MAP → 400）、`/validate`（errors 阻断启用）、路由启用/重建/启动重载前（`RouteRegistry.enable` 闸口 → 失败经 `RouteLifecycle` 自动停用 + 摘除路由/调度 + 写 `job.lastError`）三处共用 `ChainConsistencyValidator`，要求 sourceStep 指向链中**存在且序号更早**的动作（`0` = 任务输入）；前端联动改写为可选后续（T2' 引用清单）。

FieldMapper 签名变化：

```java
Map<String, Object> apply(Map<Integer, Object> context, List<FieldMappingRule> rules);
// rule.sourceStep -> context 中的源对象；rule.sourcePath -> 该对象内点号路径
```

## 3. 片段组合生成器

RouteGenerator 重构为"按动作种类追加片段"：

```java
RouteDefinition route = entry(actions);          // MQ_CONSUME 或 direct/timer 入口
for (Action a : sorted(actions)) {
    appendFragment(route, a);                    // 每个种类一个 builder 方法
}
```

每个种类的片段（伪 DSL）：

| 种类 | 片段 |
|---|---|
| `MQ_CONSUME` | `from(spring-rabbitmq:<exchange>?exchangeType=<t>&queues=..&routingKey=<rk>&autoDeclare=true)`（缺省=数据源名/direct/queue，ADR-0010）→ `unmarshal().json()` → 存 `context[0/序号]` |
| `READ_SQL` | 读水位（配置在动作上）→ `to(sql:..)` → 算新水位 → `split(body())` → 每行存 `context[序号]` |
| `FIELD_MAP` | processor：从 `context[sourceStep]` 取源 → 规则映射 → 存 `context[序号]` |
| `WRITE_UPSERT` | processor：取 body → 组装 `INSERT ... ON DUPLICATE KEY UPDATE` → jdbc 执行 |
| `HTTP_CALL` | `marshal().json()` → 设 method/鉴权头 → `to(baseUrl + path)` → processor：响应解析进 `context[序号]` + 消息体还原为本次请求体（#123/#126，失败面不变：非 2xx 抛异常、不写 context） |
| `MQ_PUBLISH` | `marshal().json()` → `to(spring-rabbitmq:name?routingKey=..)` |
| `ONE_TO_MANY`（拆分档） | processor：解析来源那片数组 → 换号（新批号 + 记下父消息标识）→ `split(每元素)`：每条子消息存 `context[本序号] = 自己那个元素`，并起自己的消息标识 / 业务键 / 批内序号 → 按开过的层数 `end()` |
| `AGGREGATE` | processor：把这条消息记成一张承运单（攒批键 = 运行 + 动作序号）→ 在**批 exchange 上同步判齐**（凑不满立即失败、绝不发半批）→ 打包 `{"<bodyField>": [ …每条各自那一片… ]}` → 过容量闸 → 批号转手，链上只留打包出来那一条消息（空批 / 整批被续投跳过时挂 `edge.batchEmpty`，由一层 filter 拦截，后面连出站都不跑） |
| `ONE_TO_MANY`（还原档） | processor：从 `context[sourceStep]`（再按 `sourcePath`）取回整份响应 → 按 `resultKeyField` 与成员业务键配对（配不上一条就整批失败，且只逐条记 FAILED、不记整批那一行）→ 每条记一行、`context[本序号] = 自己那一份` → `split(成员清单)`：把成员的身份与上下文还原回去（不换号），后续动作照旧逐条跑 |

入口判定：链首为 `MQ_CONSUME` → 南向（消费主路由 + direct 重放路由，现状保留）；否则北向（`direct:job-{id}` + 定时入口，cron 就绪后替换硬编码 timer）。

错误处理保持现状：路由级 `onException`（retryCount/backoffMs 重试）+ 消息步记录（`edge.actionIndex` 按动作定位），不做改动。

## 4. 迁移路径

存量 `job_action` 按 addition 内容推断新种类：

| 现状（REMOTE_ACCESS + addition） | 迁移为 |
|---|---|
| `sql` 非空，数据源 MYSQL | `READ_SQL`（水位策略/列从 Job 移入动作 addition） |
| `tableName` 非空 | `WRITE_UPSERT` |
| 数据源 HTTP | `HTTP_CALL` |
| 数据源 RABBITMQ 且为链首 | `MQ_CONSUME` |
| `FIELD_MAPPING` | `FIELD_MAP`（sourceStep = 前一个动作序号，sourcePath = 原 source） |

JobRequest 移除 `watermarkType`/`watermarkColumn`（移到 READ_SQL 动作，对应 DS-7 任务表单瘦身）。

## 5. 影响面清单

- `model`：`ActionType` → `ActionKind`（新增枚举 + 存量数据迁移 SQL）
- `config`：`ActionConfig` 拆为每种动作一个 record（ReadSql/WriteUpsert/HttpCall/MqConsume/MqPublish/FieldMapConfig），各自 validate（DS-2 延伸）
- `mapping`：`FieldMappingRule` 加 sourceStep；`FieldMapper` 改上下文解析
- `service`：`RouteGenerator` 重写为片段组合；`JobValidationService` 按种类校验；`MappingHistoryService` sourceKey 从 READ_SQL/MQ_CONSUME 推导；`JobService` 移除水位字段校验
- `web`：`ActionRequest/ActionView`、`JobRequest/JobView`（水位字段移除）
- `frontend`：动作表单按种类特化（DS-7），字段映射 sourceStep 选择器
- `test`：RouteGeneratorTest 按片段组合重写 + 每种类单测 + "读→映射→写→发消息"组合端到端测试

## 6. 实施顺序

1. 动作语义化（枚举 + config records + 迁移 SQL），保持现有两条链路等价；
2. 上下文列表 + FieldMapper 改造；
3. 片段组合生成器（用现有测试对照，行为不变）；
4. 水位迁移到读动作 + 任务表单瘦身；
5. 前端动作表单特化（DS-7）+ `MQ_PUBLISH` 新动作端到端验证（目标场景："同步的同时发消息"）。

## 7. 验收场景

- 现有北向/南向链路行为不变（回归测试）；
- 新场景：MySQL 读 → 字段映射 → HTTP 写 MES + `MQ_PUBLISH` 发送同一份映射结果到客户队列，一条任务完成"同步 + 通知"；
- 动作链任意位置插入动作不再被静默忽略（校验期明确报错或正常参与路由）。

## 8. 已确认决策（2026-08-19）

1. `ActionType` 升级为显式 `ActionKind` 枚举，不做"REMOTE_ACCESS + operation 字段"的混合形态。
2. 字段规则的 `sourceStep` 使用 action_index；动作重排时前端联动改写依赖规则（或校验期报警）。
3. MVP 限制：一条链最多一个 `MQ_CONSUME` + 一个带水位的 `READ_SQL`，超出在配置期校验拦截。

## 9. 刻意的选择：这几条**不是 bug**（别去修）

基数变化这条链上有四处「看着别扭、其实是有意的」口径。完整的「为什么」在 ADR-0017，这里列成一张清单，
免得下一个人把它们当缺陷修掉：

| 刻意口径 | 看着像什么 | 为什么不能改成"顺手那样" |
|---|---|---|
| **超时只当失败判据，不当发送触发** | 合并等不齐时看不见"等多久发一次"的参数；等过头就是把这一批记失败 | 时间窗 / 条数窗攒批会让「一批」变成「碰巧在同一窗口里到的那些」，与上游读取批脱钩，水位与重读范围出现第二套边界；超时冲刷半批还会漏数据。要限制条数**调读库那一步的批大小**，一处生效（ADR-0017 决策 5） |
| **整批重发只长在出站那一步** | 出站那一档只有一个「重发这一批（N 条）」，没有 N 个「重投这一条」 | 出站那一行的输入就是整批报文，「重投这一条」实际会把整批发一遍——界面 N 个按钮 = 整批发 N 遍，承诺与行为相反。合并**之前**的步骤仍是逐条重投（ADR-0017 决策 7） |
| **合并之前的单条被重投时立刻发** | 单独重投一条数据，它自己形成只有一条的「批」，不挂在那里等其他条目到齐 | 攒齐的边界是**上游读取批**（决策 5）。重投不是读取批，本来就没有别的成员会来；让它等就等于永远不发。这是决策 5 的直接结果，不是毛病（ADR-0017 后果） |
| **快照装不下就当场显式失败、不写半截** | 一批太大时动作直接失败（历史里看不到半份报文），而不是截断存一份 | 半截报文的上游快照会让断点续投在解析处报出离现场很远的错。容量界线跟着列宽走（`MEDIUMTEXT` = 16 MB，`MessageHistoryService.SNAPSHOT_LIMIT_BYTES` 与列型同源）；一批装不下就**调小读库那一步的批大小**（ADR-0015 决策 4 / ADR-0017 决策 8、#141） |
