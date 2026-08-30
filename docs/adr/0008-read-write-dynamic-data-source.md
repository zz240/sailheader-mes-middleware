# ADR-0008: 数据源按动作动态路由（客户库与配置库分离）

- 状态：已接受
- 日期：2026-08-30

## 背景

`RouteGenerator` 当前把 `READ_SQL` 的 Camel 端点写成 `sql:...?dataSource=#dataSource`（`spike/src/main/java/com/example/edgegateway/service/RouteGenerator.java:162`），
`WRITE_UPSERT` 用注入的全局 `jdbcTemplate.update(...)`（:357）——两者运行时都固定打向网关自身的配置库（Spring `#dataSource`），
**忽略动作配置里选的 `dataSourceId`**。demo 里配置库 = 客户库（`edge_gateway`，ADR-0004），掩盖了问题；一旦客户库独立部署，
`READ_SQL` 会从配置库读（查不到客户表）、`WRITE_UPSERT` 会把数据写进配置库——**读/写错库**。

## 决策

引入按动作的动态数据源，动作的 `dataSourceId` 在运行期真正生效：

- 新增 `DataSourceManager`：启动/重载时为每个 MySQL 数据源建一个**命名连接池**（HikariCP）并注册为 Camel 可解析的 bean
  （`edge-ds-<id>`）；数据源**增/删/改**时销毁重建该池，并**重建引用它的启用任务路由**（沿用"动作变更重建路由"的机制）。
- `READ_SQL`：端点改为 `sql:...?dataSource=#edge-ds-<id>&outputType=SelectList`（路由构建期拼 bean 名）。
- `WRITE_UPSERT`：改用 `DataSourceManager.get(id)` 返回的 `DataSource` 构造的 `JdbcTemplate`。
- 网关自身配置库（`job`/`data_source`/`template`/`watermark`/`dead_letter`）仍由 Spring `#dataSource` 承担，与客户库**分离**。
- 凭证加密（DS-3）仍**暂缓**（明文入池），作为独立待办，不并入本次。
- 客户库读写**不走 MyBatis-Plus**：WRITE_UPSERT 用 `JdbcTemplate`（客户表任意、列动态、MP 又绑单一 SqlSessionFactory），配置库才用 MP。

## Considered Options

- **A（选定）每库一个命名池**：启动即建池、稳定 bean 名，路由构建期可直接引用。
- **B 惰性池缓存**：`DataSourceManager` 按 id 惰性建池并缓存；同样可行但路由构建期需确保 bean 已注册，时序更绕。
- **C 每次临时建连**：简单但无池、连接开销大，且 Camel `sql` 端点难直接塞对象。

## Consequences

- 需要处理**生命周期与时序**：池必须先注册进 Camel 注册表，再构建路由（`reloadEnabledJobs` 之前要先把池建好）。
- 连接池资源随数据源数量增长；站点级数据源数量少，可接受。
- 验证需要**一个与配置库不同的客户库**（第二个 MySQL/不同库名），端到端证明读写确实打到客户库。
