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
├── CONTEXT.md                  # 领域词汇表
├── AGENTS.md                   # 给 agent 的仓库约定
├── docs/
│   ├── edge-gateway-design.md  # 设计文档（已定稿）
│   ├── mock-contract.md        # 环境 mock 契约
│   ├── adr/                    # 架构决策记录
│   └── agents/                 # issue tracker / triage / domain 配置
└── spike/                      # Spring Boot + Camel Spike 工程
    ├── docker-compose.yml      # 本地 MySQL + RabbitMQ
    ├── db/init.sql             # 建表与种子数据
    └── src/                    # 两条演示链路 + 字段映射 + mock MES
```

## 快速运行 Spike

前置：JDK 21、Docker。

```powershell
# 1. 启动本地 MySQL 和 RabbitMQ
cd spike
docker compose up -d

# 2. 启动网关
..\.tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

验证：

- 北向（MySQL → mock MES）：`GET http://localhost:8080/mock/mes/received` 能看到映射后的采购单数据。
- 南向（RabbitMQ → MySQL）：向队列 `mes.to.edge` 发一条 JSON，应用会幂等 upsert 到 `po_receipt`。

## 状态

- 设计定稿（`docs/edge-gateway-design.md` + ADR-0001~0004）
- Spike 已端到端跑通（MySQL → 字段映射 → mock MES；RabbitMQ → 字段映射 → MySQL 幂等 upsert）
- 进行中：M0（配置模型 + REST + 路由生成器 + 表单 UI）

## 参考

早期需求与详细设计参考仓库：[sailheader-mes-middleware-design](https://github.com/zz240/sailheader-mes-middleware-design)（独立仓库，未包含在本仓库内）。
