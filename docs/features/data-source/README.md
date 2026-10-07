# 数据源

数据源 = **连接实例**（地址 + 鉴权），不绑定具体表/队列/接口路径（那些在动作里配）。

## 当前设计（现状）

- **模型**：`DataSource(id, siteId, name, type, address, addition)`，`type` ∈ HTTP/MYSQL/RABBITMQ。
- **连接级（ADR-0007）**：
  - HTTP：`address` 为根地址（scheme://host:port），`addition` 仅 `{"token":"..."}`（可选，Bearer 鉴权）；`path`/`method` 在动作里配，RouteGenerator 拼 base+path；
  - MySQL：`address` 为 jdbc 串，`addition` 仅 `username/password`（`url` 取 `address`，已去冗余）；
  - RabbitMQ：`address` 为 broker，`addition` `host/port/username/password` 有默认值（localhost/5672/guest/guest）；`queue` 在动作里配。
- **强类型校验（DS-2）**：`DataSourceConfig` sealed interface（HTTP/MySQL/RabbitMQ 各自 record），`from()` 拒绝未知键（400）、`validate()` 做必填与取值校验；`DataSourceRequest` 按 `type` 手工分派（Jackson EXTERNAL_PROPERTY 对 record 不可用）。
- **敏感字段存储**：MySQL 密码等目前为**明文**落库（代码无 AES/Cipher/MasterKey 实现，也无 `EDGE_MASTER_KEY`）；响应同样回显明文。凭证脱敏（DS-3）、密钥丢失恢复（DS-4）均未做。
- **结构探测**：`StructureProbeService` 连库读表结构，失败回退样例 JSON；返回字段列表（名称/类型/可空/样例）供映射编辑器用。
- **代码位置**：`config/datasource/DataSourceConfig*.java`（类型化配置）、`service/DataSourceService.java`、`service/ConnectionTester.java`、`service/StructureProbeService.java`、`web/DataSourceController.java`。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | addition 类型化（按 type 分派强类型校验） | 每种数据源有独特处理方式；未知键/值级校验有缺口 | — | DS-2 / da5afc9 |
| 08-19 | 数据源粒度统一为连接级（方案 A） | MySQL 连接级、MQ/HTTP 资源级导致凭证重复、模板不统一 | — | DS-6 / ADR-0007 / 213e0db |
| 08-18 | 响应脱敏（凭证不出网） | 明文密码随响应回前端 | 暂缓（内部使用） | DS-3 |
| 08-18 | 密钥丢失恢复端点 | master key 不匹配时 update/delete 全 500 无自救路径 | 暂缓（同 DS-3） | DS-4 |

## 待办 / 已知限制

- 凭证脱敏（DS-3）、密钥丢失恢复（DS-4）：暂缓未做；
- （已清除）MySQL `url` 与 `address` 冗余已于 08-30 去掉。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-19 | addition 类型化（按 type 分派强类型校验） | 每种数据源有独特处理方式；未知键/值级校验有缺口 | — | DS-2 / da5afc9 |
| 08-19 | 数据源粒度统一为连接级（方案 A） | MySQL 连接级、MQ/HTTP 资源级导致凭证重复、模板不统一 | — | DS-6 / ADR-0007 / 213e0db |
| 08-18 | 响应脱敏（凭证不出网） | 明文密码随响应回前端 | 暂缓（内部使用） | DS-3 |
| 08-18 | 密钥丢失恢复端点 | master key 不匹配时 update/delete 全 500 无自救路径 | 暂缓（同 DS-3） | DS-4 |

## 当前生效

- 连接级模型（ADR-0007）
- typed addition + 未知键/值级校验（DS-2）
- MySQL `url` 冗余已消除（url 取 address，08-30）
- 凭证脱敏/密钥恢复：未做（待办）
