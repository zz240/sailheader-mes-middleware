# ADR-0014: SQL Server 数据源类型与 SQL 方言收敛

- 状态：已接受
- 日期：2026-09-18

## 背景

客户侧的真实形态是**金蝶/用友 ERP（SQL Server）**：`docs/design/docking-scenarios-spec.md` 里 A2（主数据/BOM 下发）、A3（工单下发）的落点、
A5（采购收料回传）、A7（试模/质检报告回传）的源都在这个库上。而本产品今天只认 MySQL，共七处：

| 处 | 位置 | 今天的表现 |
|---|---|---|
| 数据源类型 | `model/DataSourceType.java:4-8` | 只有 `HTTP/MYSQL/RABBITMQ` |
| 凭证配置 | `config/DataSourceConfig.java:12-30`、`config/MysqlDataSourceConfig.java` | sealed 接口只 permits 三种；`from` 没有 `SQLSERVER` 分支 |
| 连接池 | `service/DataSourceManager.java:53,77` | 只给 `MYSQL` 建池，其余直接返回 null |
| 写路径取池 | `service/RouteGenerator.java:616-620` | `customerDataSource` 对非 MySQL 抛「动作引用的数据源不可用（非 MySQL 或未配置）」 |
| 读分页 | `service/ReadSqlBatcher.java:78-84` | 拼 `ORDER BY <col> LIMIT n` |
| 幂等 upsert | `service/RouteGenerator.java:588-599` | 拼 `insert ... on duplicate key update ... values(col)` |
| 结构探测 | `service/StructureProbeService.java:70,114-116` | 非 MySQL 抛「该数据源类型不支持结构探测（当前仅 MySQL）」；SQL 用 `SELECT * FROM t LIMIT 20` |
| 只读校验 | `service/ReadOnlySqlValidator.java:17-18` | `SELECT * FROM ( sql ) __probe LIMIT 0`，签名上就写死 `MysqlDataSourceConfig` |
| 连接测试 | `service/ConnectionTester.java:35-39` | `switch` 只覆盖三种配置 |
| 驱动 | `spike/pom.xml:73-76` | 只有 `mysql-connector-j` |

所以这不是「加个驱动」：即便驱动在，读分页与 upsert 拼出来的都是 MySQL 语法，在 SQL Server 上是语法错误；
`JobValidationService.java:102` 那类「按数据源类型判断能不能校验」的分支也会漏掉新类型。

## 决策

1. **新增数据源类型 `SQLSERVER`**，与 `MYSQL` 并列，不复用 `MYSQL` + URL 猜方言。
2. **凭证独立成 `SqlServerDataSourceConfig`**（字段与 MySQL 同为 `username/password`）：保留独立 record 是为了将来加
   SQL Server 特有的连接属性（`encrypt`、`trustServerCertificate`、`instanceName`），不被「两个一样的 record」这个理由挡住。
3. **方言收敛成一个模块**：`service/sql/SqlDialect` 接口 + `MySqlDialect` / `SqlServerDialect` 两个实现，
   由 `SqlDialects.of(DataSourceType)` 取。四处站点（读分页、幂等 upsert、结构探测、只读校验）全部改走方言，
   **方言只有这一份实现**；将来加 Oracle/达梦只加一个实现类。
4. **驱动显式钉版本 `7.4.1.jre8`**：本机 `.m2` 里只有这一版（离线可用）。Spring Boot BOM 指的版本不在本地，
   跟着 BOM 走会让 `mvn -o` 构建失败。
5. **前端同步**：数据源类型下拉、addition 表单分支、地址提示、结构探测入口按钮、
   以及 `JobsView.vue:371-372` 里「动作允许的数据源类型」白名单，都加上 SQL Server。

方言四处的对应实现：

| 用途 | MySQL（现状） | SQL Server（新增） |
|---|---|---|
| 读分页 | `ORDER BY <col> LIMIT n` | `ORDER BY <col> OFFSET 0 ROWS FETCH NEXT n ROWS ONLY`（2012+） |
| 结构探测 | `SELECT * FROM t LIMIT 20` | `SELECT TOP 20 * FROM t` |
| 只读校验 | `SELECT * FROM ( sql ) __probe LIMIT 0` | `SELECT TOP 0 * FROM ( sql ) __probe` |
| 幂等 upsert | `insert ... on duplicate key update c = values(c)` | `MERGE INTO t WITH (HOLDLOCK) USING (VALUES (?,?)) AS src(c1,c2) ON ... WHEN MATCHED THEN UPDATE ... WHEN NOT MATCHED THEN INSERT ...` |

> SQL Server 的 upsert 用 `MERGE + HOLDLOCK` 而不是 `UPDATE ... IF @@ROWCOUNT=0 INSERT`：后者在并发下有竞态。
> `USING (VALUES (?,?)) AS src(c1,c2)` 这个写法是为了避开 SQL Server 对「只在 `USING` 里出现的参数」推断不出类型的毛病。
> `MERGE` 自身的并发语义仍有已知坑（官方文档也提示），这与 ADR-0003「不承诺跨系统补偿」的务实口径一致：幂等键保证重复执行不产生重复行。

## Considered Options

- **A（选定）新增类型 + 方言模块**：类型名说实话（界面选 SQL Server 就是 SQL Server），`JobValidationService` 那类
  按类型判断的地方语义成立，方言有唯一落点、可单测。
- **B 沿用 `MYSQL` 类型按 JDBC URL 判方言**：改动最小，但类型名会说谎（界面写 MySQL、实际连 SQL Server），
  探测/校验的报错含糊，且「按类型判断能不能做」的分支全部失效。
- **C 让 Camel `sql:` 组件承担方言差异**：`sql:` 端点没有按数据源切方言的能力，upsert 本来就是我们自建 SQL，
  组件帮不上忙；引入它反而多一层间接。

## 结果

- **影响面**：后端 11 个文件（类型/配置/连接池/连接测试/四处方言站点/校验分支/运行时取池）、前端 3 个文件、`pom.xml` 一处。
- **可测性**：方言两个实现各自单测（不连库就能钉住 SQL 文本）；真机验收靠 `tools/shadow-site/` 的 SQL Server 容器（容器 `edge-shadow-mssql`）。
- **默认语义不变**：仍然是「一条消息 = 一行 = 一张表」（`RouteGenerator.upsert`）。
  **补记（#140 / #142 落地后）**：把一条消息拆成多行写的「分裂」能力**已落地**为 `ONE_TO_MANY` 的拆分档；
  批量发送那一侧的 `AGGREGATE`（合并）与还原档见同一份 ADR。
  决策在 `docs/adr/0017-cardinality-change-and-message-history.md`、
  动作种类与链上行为在 `docs/action-chain-design.md` §1；要拆得在链上**显式**摆一个拆分动作，默认行为一个字没改。
  场景侧的落库形态选项见 `docs/design/docking-scenarios-spec.md` 的「落库形态的硬约束」。
- **风险**：`mssql-jdbc 7.4.1.jre8` 是 2019 年版本，遇到 TLS/新特性问题需要联网取新版驱动；
  离线环境只能用这一版，属于已知约束。
- **待观察**：SQL Server 的 `ORDER BY ... OFFSET/FETCH` 与现有 keyset 分页（`col > ?`）的配合；
  `MERGE` 在高并发下的锁行为只在影子站点做单点验证，不做压测承诺。
