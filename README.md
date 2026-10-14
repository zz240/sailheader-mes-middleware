# 边缘数据网关 edge-management

部署在客户边缘侧、面向制造企业的数据对接网关。它把本公司的 MES 等自有系统与客户的 MySQL 数据库、HTTP 接口、RabbitMQ 之间的数据同步，配置成可复用、可沉淀的任务（任务 → 动作链 → 数据源 → 字段映射），让实施工程师快速部署、快速改配置。

## 核心设计

- **引擎**：Apache Camel 作为嵌入内核（Spring Boot 运行），不自研集成引擎；错误处理、重试、类型转换复用 Camel 内置能力。
- **通信**：南向（云上 MES → 边缘）用 RabbitMQ 订阅消费；北向（边缘 → 云上 MES）用边缘主动出站 HTTP。
- **配置**：数据源 / 任务 / 动作 / 模板，表单式配置界面；字段映射用"来源步骤 + 字段路径点选"，不暴露占位符字符串。
- **可靠性**：失败即停 + 重试退避 + 死信记录；增量水位 + 业务主键 upsert 幂等。
- **存储**：配置库与客户库统一 MySQL。

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

- 文档分层：用户看 `docs/user-guide.md`；开发者看 `docs/features/`（每个功能的现状 + 决策时间线）；
  决策"为什么"在 `docs/adr/`；业务↔代码走查在 `docs/flows/`；历史草案已归档到 `docs/archive/`。
- **已落地**：
  - 数据源（HTTP / MySQL / SQL Server / RabbitMQ，**连接级**：地址与鉴权在数据源，表 / 队列 / 路径在动作）；
  - 任务与动作链配置界面；**八种动作种类**：`READ_SQL`、`WRITE_UPSERT`、`HTTP_CALL`、`MQ_CONSUME`、
    `MQ_PUBLISH`、`FIELD_MAP`、`ONE_TO_MANY`（拆分 / 还原两档）、`AGGREGATE`（合并）；
  - 字段映射：规则树编译成 JSONata 求值，配只读草稿校验与试算；
  - 消息历史（`job_run` / `message_step` + 批次三格）、断点重投与重跑任务；
  - 外部 API Key 触发、定时调度、模板 / 站点 / 映射历史沉淀。
- **交付**：单镜像同源运行（前端 `dist` 打进 Spring Boot 镜像），Flyway 版本化迁移 V1–V7。
- **测试**：后端 `mvn -o -B test` **624 项 0 失败**（H2 内存库，不需要 MySQL / RabbitMQ）；
  前端纯模块自测（`node --experimental-strip-types`，不依赖浏览器）；
  真机脚本在 `tools/shadow-site/`（影子站点 + MES 替身）与 `tools/ui/`（Playwright 页面级）。
- **已知债务**：目录名 `spike/`（见上「命名提醒」）；`RouteGenerator` 曾是改动热点（#145 已按片段与簿记拆开：装配 400 行 + `StepLedger` / 各片段类，最长的 `StepLedger` 318 行）；
  链一致性只校验 `sourceStep` 的**存在性与先后**，不校验语义漂移（重排后同序号换动作仍会放行）。

## 参考

早期需求与详细设计参考仓库：[sailheader-mes-middleware-design](https://github.com/zz240/sailheader-mes-middleware-design)（独立仓库，未包含在本仓库内）。
