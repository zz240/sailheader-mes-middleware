# ADR-0007: 数据源连接级统一（方案 A）

- 状态：已接受
- 日期：2026-08-19

> - 已收紧：2026-08-30 把 `RabbitMqDataSourceConfig` 的 `queue` 移除（不再接受该字段），队列 / exchange /
>   routingKey 归动作（`MqConsumeConfig` / `MqPublishConfig`）；数据源仅存 broker 与凭证。"动作缺 queue 时回退
>   数据源原值"的过渡回退一并移除。启用时由 `DataSourceAdditionMigration` 清理存量 `addition` 中的
>   RABBITMQ `queue`/`exchange`/`routingKey` 与 MYSQL `url`（幂等）。

## 背景

数据源粒度不一致：MySQL 是连接级（表在动作 SQL），MQ/HTTP 是资源级（队列/接口路径绑死在数据源 addition）。导致同一系统多接口/多队列需要重复配置凭证、模板复用不统一。见代码审查笔记 DS-6。

## 决策

数据源 = 连接实例（地址 + 鉴权），具体资源归动作：

- **HTTP**：`address` 为根地址（scheme://host:port）；`path` + `method` 移入动作 addition；RouteGenerator 组装 base + path；Bearer token 保留在数据源（鉴权属连接级）。
- **MQ**：`address` 为 broker；`queue`（/exchange/routingKey）移入动作 addition；broker 账号密码保留在数据源。
- **MySQL**：不变（表已在动作 SQL）。

动作缺 path/method/queue 时回退数据源原值，存量数据迁移后逐步收紧。

## 结果

凭证只存一份（换域名/broker 只改一处）、模板复用统一、同系统多接口/多队列不再重复凭证。动作契约变更与 DS-2（addition 类型化）同批实施，成本最低点。
