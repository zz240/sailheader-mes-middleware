# ADR-0010: MQ 消费绑定可配置（exchange 名称/类型/binding 归动作）

## 状态

- **采纳**（2026-09-07，grill-with-docs 决议 + 事实研究）
- 相关：ADR-0007（数据源连接级）、`docs/action-chain-design.md`、`docs/flows/southbound-sync.md`

## 背景

- 南向消费端点固定用「数据源名」作直连 exchange（`RouteGenerator.buildConsumerEntry`），
  动作侧 `MqConsumeConfig` 虽有 `exchange` 字段但**从未生效**（前端可填、落库、路由不用）；
- MES 发布方实际会使用与数据源名不一致的 exchange 名（环境/项目标准命名）与 topic 类型——
  现状网关收不到，且静默不生效（填了没反应，无任何提示）；
- 不对称：生产侧 `MQ_PUBLISH` 已要求显式 exchange（ADR-0007 收紧），消费侧却不可配。

## 决策

- **完整绑定语义**：exchange **名称** + **类型**（`direct`/`topic`/`fanout`/`headers`）+ **binding key** 全部可配；
- **配置位置 = 动作侧**（`MQ_CONSUME` action addition）：数据源仅存 broker+凭证，延续 ADR-0007；
- **缺省兼容**：不配新字段 = 数据源名 + `direct` + `routingKey=queue`，存量任务零影响；
- **实现形态 = Camel 端点参数**（不改 Camel 代码、不自建 RabbitAdmin）：
  `from("spring-rabbitmq:<exchange>?exchangeType=<t>&queues=<q>&routingKey=<rk>&autoDeclare=true")`
  —— `autoDeclare=true` 底层经 `AmqpAdmin` 按 `ExchangeBuilder(exchangeName, exchangeType)` 声明 exchange、
  `Binding(queue, exchange, routingKey)` 建立绑定；`routingKey` 即 binding key（topic 用模式串，fanout 由 broker 忽略）。
  依据：`docs/research/camel-spring-rabbitmq-declaration-facts.md`（camel-4.10.2 tag 源码核对）。

## 边界（写入文档的已知限制）

- 一个端点 = 一个 exchange 名 + 一个类型 + 一个 binding key（同队列多绑定 / 多队列不同 key →
  需自建 `RabbitAdmin` 声明（Exchange+Queue+Binding，依赖已有），列为 V2 评估）；
- `args`（`arg.exchange.*`/`arg.queue.*` 如队列持久化等）暂不透传；队列/交换机声明参数沿用现状默认；
- 若改为 `autoDeclare=false` 集中管理拓扑，同属 V2（声明职责转移）。

## 锚点

- grill 决议：需求真实（自定义 exchange 会用）/ 深度 B / 动作侧 / 缺省兼容 / 2026-09-07；
- 事实研究：`docs/research/camel-spring-rabbitmq-declaration-facts.md`；
- 建议表来源：`docs/flows/southbound-sync.md`「消费者 exchange 未暴露」（业务改进，已闭环 #80-83）。
