# 动作链通用化设计（DS-8 细化稿）

> 状态：已确认（2026-08-19）。关联：DS-2（typed addition）、DS-6（数据源连接级）、DS-7（动作表单重设计）。
> 目标：把 RouteGenerator 从"两个固定模板"改为"动作片段组合"，使"同步的同时发消息"等真实场景可配置。

## 1. 动作语义化：动作种类目录

把 `ActionType`（REMOTE_ACCESS / FIELD_MAPPING）升级为显式语义的动作种类，每个种类有自己的配置 record 与校验（复用 sealed `ActionConfig` 模式）：

| 动作种类 | 语义 | 在路由中的位置 | 关键参数（typed addition） |
|---|---|---|---|
| `MQ_CONSUME` | 南向入口：从队列消费 | 只能作链首 | 数据源（broker）+ queue |
| `READ_SQL` | 从 MySQL 增量/全量读 | 链中部（北向通常首位） | 数据源 + SQL + 水位策略 + 水位列 |
| `FIELD_MAP` | 字段映射 | 任意 | rules（sourceStep + sourcePath + target + transform） |
| `WRITE_UPSERT` | MySQL 幂等 upsert | 任意 | 数据源 + tableName + idempotencyKey |
| `HTTP_CALL` | 调 HTTP 接口 | 任意 | 数据源（根地址）+ path + method + 鉴权 |
| `MQ_PUBLISH` | 发 MQ 消息 | 任意 | 数据源（broker）+ queue/exchange + routingKey + bodySourceStep（必填） |

设计要点：

- 读写意图**显式化**，不再靠"位置 + addition 内容"猜测；
- `MQ_CONSUME` 只能是链首（路由入口），其余动作可在任意位置；MVP 限制一条链最多一个 `MQ_CONSUME` 和一个带水位的 `READ_SQL`（校验期拦截）；
- 数据源引用全部是"连接级"（DS-6 方案 A）：HTTP 数据源存根地址、路径在动作里；MQ 数据源存 broker、队列在动作里。

## 2. 上下文列表

`Exchange` property `edge.context`：`Map<Integer, Object>`，键 = 动作序号（action_index），`0` 为任务输入（Job Input）。

- 每个动作完成后写入 `context[actionIndex] = 输出`；
- `READ_SQL` 的 split 后，每个子 exchange 的 `context[readIndex]` = 该行数据；
- 字段映射规则引用改为结构化 `sourceStep + sourcePath`（不再拼接路径字符串），`sourceStep` 默认等于"前一个动作的序号"（迁移时按原链推断）；
- `MQ_PUBLISH` 的请求体来源 = `bodySourceStep`（**必填**，显式指向链中存在的更早动作，校验期检查；发布时该步无输出——如被断点跳过——则执行失败，不静默发旧值）；`HTTP_CALL` 直接使用当前 body（上一步输出）；
- 重排动作时，前端负责同步改写依赖规则的 sourceStep（或校验期报警），避免静默错位。

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
| `MQ_CONSUME` | `from(spring-rabbitmq:name?queues=..&routingKey=..)` → `unmarshal().json()` → 存 `context[0/序号]` |
| `READ_SQL` | 读水位（配置在动作上）→ `to(sql:..)` → 算新水位 → `split(body())` → 每行存 `context[序号]` |
| `FIELD_MAP` | processor：从 `context[sourceStep]` 取源 → 规则映射 → 存 `context[序号]` |
| `WRITE_UPSERT` | processor：取 body → 组装 `INSERT ... ON DUPLICATE KEY UPDATE` → jdbc 执行 |
| `HTTP_CALL` | `marshal().json()` → 设 method/鉴权头 → `to(baseUrl + path)` |
| `MQ_PUBLISH` | `marshal().json()` → `to(spring-rabbitmq:name?routingKey=..)` |

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
