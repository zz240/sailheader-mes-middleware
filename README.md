# 边缘数据网关 edge-management

部署在客户边缘侧、面向制造企业的数据对接网关。它把本公司的 MES 等自有系统与客户的 MySQL 数据库、HTTP 接口、RabbitMQ 之间的数据同步，配置成可复用、可沉淀的任务（任务 → 动作链 → 数据源 → 字段映射），让实施工程师快速部署、快速改配置。

## 它为什么长这样

一句话：把「本公司 MES」和「客户那一套系统」之间的数据同步，从写代码变成写配置。一套部署服务一个客户，配完就能跑，改需求不用重新发版。

### 它分几层

| 层 | 在哪 | 干什么 | 只依赖 |
|---|---|---|---|
| 界面 | `frontend/src/views/` | 四个页面：数据源 / 任务 / 消息历史 / 站点 | 接口层 |
| 接口 | `web/*Controller` | 收请求、校验入参、统一返回 `{code,message,data}` | 服务层 |
| 服务 | `service/*Service` | 配置增删改查、链一致性校验、消息历史查询、触发 | 执行层、存储 |
| 执行 | `service/RouteGenerator`、`config/action/*`、`mapping/*` | 配置编译成 Camel 路由；消息到达时按片段执行、做字段映射 | 存储 |
| 存储 | MySQL（Flyway `V1..V9`）、RabbitMQ | 配置、运行、消息步；南向的队列 | — |

依赖只往下，不往上：改界面碰不到执行层；改执行层要先确认配置形状没变（配置是这两层之间唯一的契约）。

一条消息怎么走：MQ 或定时触发 → `RouteGenerator` 建好的路由 → 按序号过一个个动作片段 → 字段映射编译求值 → 写库或出站 → 每一步记一条 `message_step`。

### 三条主流程

1. **配置**：建数据源 → 建任务与动作链 → 配置期校验 → 启用时编译成路由。
2. **北向（上行）**：读客户 MySQL（按水位增量）→ 字段映射 → HTTP 推给 MES。
3. **南向（下行）**：订阅 MQ → 字段映射 → 按业务键幂等写回客户库。

数据在链上是一条条「消息」。一次运行里，一条消息每穿过一个动作就留下一个「消息步」，消息历史就是这些步的集合。

### 五个概念

| 概念 | 一句话 |
|---|---|
| 数据源 | 一个外部连接（库 / broker / HTTP 服务），只放地址与鉴权 |
| 任务 / 动作链 | 一条同步流程，和它按序号执行的动作序列 |
| 字段映射规则 | 「来源字段路径 → 目标字段 + 转换」，字段映射唯一的存储单元 |
| 水位 | 上次增量同步走到哪；失败不推进 |
| 消息 / 消息步 | 链上流转的一条数据，和它穿过每个动作的一次结果 |

其余的词在 `GLOSSARY.md`。

所有配置与运行记录都在 MySQL 里（配置库）。客户库另配，可以和配置库是同一个实例，也可以是不同实例（ADR-0004 / ADR-0008）。

### 两条不变量

- **失败即停**：任何一步失败，水位不推进，重跑从未完成的那批重新走。代价是目标端会再收到一次，靠业务键幂等兜住。
- **错误提前**：配置期能查出来的错，不放到运行时。SQL 与映射字段在保存或启用时校验。

这两条决定了错误处理与重试的整个形状。要改任何一处，先确认这两条还成立。

### 三个「为什么不用 X」

- **不自研集成引擎**，用嵌入式 Apache Camel：错误处理、重试、类型转换直接复用（ADR-0001）。
- **不让用户写占位符字符串**，用「来源步骤 + 字段路径点选」：占位符在配置期查不出错（ADR-0011）。
- **连接信息不进动作**，放在数据源上：一个客户库一个连接，动作只说表或队列（ADR-0007）。

被否掉的方案都在 `docs/adr/` 里，标题本身就说明了否掉了什么。

### 公认的坑

- 链一致性只校验 `sourceStep` 的存在与先后，不校验语义漂移：重排后同序号换动作仍会放行。
- `spike/` 是正式后端的目录名，早期遗留，不改名的理由见下面的「命名提醒」。
- 文档引用曾大面积失效，自动校验还没做（#149）。

### 下一步往哪长

边缘侧只做执行。沉淀类能力（模板、映射建议）本质是「跨客户复用」，应该上移到云上产品，不在边缘重复造。这是判断一个新需求该不该进本仓库的第一道尺子。

## 目录结构

```text
.
├── GLOSSARY.md                  # 领域词汇表
├── AGENTS.md                   # 给 agent 的仓库约定
├── docs/
│   ├── user-guide.md           # 用户操作手册（当前功能）
│   ├── action-chain-design.md  # 动作链通用化设计（DS-8 细化稿）
│   ├── adr/                    # 架构决策记录
│   ├── features/               # 功能演化页（现状 + 决策时间线）
│   ├── flows/                  # 数据流走查地图（业务↔代码对应 + 建议表）
│   ├── archive/                # 已归档的过时文档（历史草案等）
│   └── agents/                 # issue tracker / triage / domain 配置
├── frontend/                   # 前端工程（Vue 3 + TS + Vite + Element Plus）
└── spike/                      # 后端工程（Spring Boot + Camel）——就是正式后端，见下方命名提醒
    ├── docker-compose.yml      # MySQL + RabbitMQ + app（多阶段构建）
    ├── Dockerfile              # 多阶段构建（node 构建前端 → mvn 打包 → JRE 镜像）
    └── src/                    # 动作链 / 字段映射 / 消息历史 / mock MES
        └── main/resources/db/migration/   # Flyway 版本化迁移（V1 建表+种子，V2+ 增量）
```

> **命名提醒**：`spike/` 是**正式后端**工程（Spring Boot + Apache Camel），不是一次性验证代码——
> 目录名是早期"先把链路跑通"阶段留下的。改名要动构建脚本、文档与工具脚本里的全部路径引用，
> 收益只是消除误解，因此**暂不改名**，在此说明。

## 快速运行

前置：JDK 21、Node 22、Docker。

### 一键交付（推荐）

```powershell
cd spike
docker compose up -d --build   # 构建并启动 MySQL + RabbitMQ + app（前端后端一体镜像）
# 访问 http://localhost:8080
```

### 开发模式（前后端分离）

```powershell
# 1. 本地 MySQL 和 RabbitMQ
cd spike
docker compose up -d mysql rabbitmq

# 2. 后端（8080）
..\.tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run

# 3. 前端（5173，代理 /api 到 8080，热更新）
cd ..\frontend
npm install
npm run dev
```

验证：

- 北向（MySQL → mock MES）：`GET http://localhost:8080/mock/mes/received` 能看到映射后的采购单数据。
- 南向（RabbitMQ → MySQL）：向队列 `mes.to.edge` 发一条 JSON，应用会幂等 upsert 到 `po_receipt`。

## 状态

- 文档：**先看 [`docs/README.md`](docs/README.md)**（按「我要做什么」分类的总索引）。用户手册是
  `docs/user-guide.md`；功能现状在 `docs/features/`；决策「为什么」在 `docs/adr/`；业务↔代码走走在 `docs/flows/`。
- **已落地**：
  - 数据源（HTTP / MySQL / SQL Server / RabbitMQ，**连接级**：地址与鉴权在数据源，表 / 队列 / 路径在动作）；
  - 任务与动作链配置界面；**八种动作种类**：`READ_SQL`、`WRITE_UPSERT`、`HTTP_CALL`、`MQ_CONSUME`、
    `MQ_PUBLISH`、`FIELD_MAP`、`ONE_TO_MANY`（拆分 / 还原两档）、`AGGREGATE`（合并）；
  - 字段映射：规则树编译成 JSONata 求值，配只读草稿校验与试算；
  - 消息历史（`job_run` / `message_step` + 批次三格）、断点重投与重跑任务；
  - 外部 API Key 触发、定时调度、站点级配置沉淀。
- **交付**：单镜像同源运行（前端 `dist` 打进 Spring Boot 镜像），Flyway 版本化迁移 V1–V7。
- **测试**：后端全量跑 `pwsh -File tools/run-tests.ps1`（H2 内存库，不需要 MySQL / RabbitMQ）；
  前端纯模块自测（`node --experimental-strip-types`，不依赖浏览器）；
  真机脚本在 `tools/shadow-site/`（影子站点 + MES 替身）与 `tools/ui/`（Playwright 页面级）。
  用例数不写进 README，以 CI 或本地跑出来的结果为准。
- **已知债务**：目录名 `spike/`（见上「命名提醒」）；`RouteGenerator` 曾是改动热点（#145 已按片段与簿记拆开：装配 400 行 + `StepLedger` / 各片段类，最长的 `StepLedger` 318 行）；
  链一致性只校验 `sourceStep` 的**存在性与先后**，不校验语义漂移（重排后同序号换动作仍会放行）。

## 参考

早期需求与详细设计参考仓库：[sailheader-mes-middleware-design](https://github.com/zz240/sailheader-mes-middleware-design)（独立仓库，未包含在本仓库内）。
