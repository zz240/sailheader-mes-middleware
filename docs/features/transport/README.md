# 通信模型（南北向）

南向典型形态 = MQ 消费入口；北向典型形态 = 边缘出站 HTTP；方向只是业务标签。

## 当前设计（现状）

- **非对称模型（ADR-0002）**：云上无法主动连边缘 → 南向入口用 MQ 订阅消费；边缘主动出站 HTTP → 北向推送。**典型形态，非强制绑定**。
- **入口决定路由形态（DS-9 澄清）**：不做方向↔入口一致性校验；入口由链首动作种类决定：
  - MQ_CONSUME 链首 → `spring-rabbitmq:<数据源名>?queues=<queue>&routingKey=<queue>&autoDeclare=true`（消费者，queue 自动声明并与同名直连 exchange 绑定）；
  - READ_SQL 链首 → timer + direct 轮询；
  - 手动执行/重投 → `direct:job-<id>` 重放。
- **sourceKey（映射历史隔离）**：按**实际源动作**推导（MQ_CONSUME → `mq:<queue>`、READ_SQL → `db:<table>`），与 direction 解耦。
- **反例场景**：MQ 消费入口 + NORTH 方向（客户发 MQ → 边缘消费 → 推 MES HTTP）已被 DS-9 端到端验证支持。
- **代码位置**：`service/RouteGenerator.java`（buildConsumerEntry / buildPollingEntry / buildReplay）、`service/MappingHistoryService.java`（sourceKey）。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-16 | 南向 MQ + 北向出站 HTTP 非对称模型 | 云上够不到边缘；MES 已有 HTTP 入站 | 语义被 DS-9 澄清 | ADR-0002 |
| 08-21 | 方向显式声明，不做方向↔入口校验 | 反例：MQ 消费入口 + 北向推送完全可能 | — | DS-9 / 67ee48c |

## 待办 / 已知限制

- 消费者 exchange 未暴露（固定用数据源名作为直连 exchange），如需自定义 exchange/routingKey 绑定需扩展；
- MQ 生产（MQ_PUBLISH）要求显式 exchange（amq.default 不可靠，实测结论 d7790b1）。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-16 | 南向 MQ + 北向出站 HTTP 非对称模型 | 云上够不到边缘；MES 已有 HTTP 入站 | 语义被 DS-9 澄清 | ADR-0002 |
| 08-21 | 方向显式声明，不做方向↔入口校验 | 反例：MQ 消费入口 + 北向推送完全可能 | — | DS-9 / 67ee48c |

## 当前生效

- 入口由链首动作决定（MQ_CONSUME 消费 / READ_SQL 轮询 / direct 重放）
- 方向 = 业务标签，不绑定传输形态
