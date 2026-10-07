# 数据流走查地图（场景索引）

本目录是 `docs/` 下的"业务 ↔ 代码"走查地图。做法：从项目**代码与文档**归纳可独立走查的业务流程（场景），
每个场景一个产出文件（固定六段：场景说明 / 流程详解（含时序图）/ 测试环境 / 测试数据与造数指导 / 验证操作 / 建议表）。
术语一律采用 `CONTEXT.md` 定义；决策依据见 `docs/adr/`；功能现状见 `docs/features/`。

> 走查只产出文档与建议清单，不实施业务建议（缺陷修复除外）；建议按 Matt 流程路由到对应技能，见文末。

## 场景清单（从项目代码与文档归纳）

| 场景 | 业务链路（入口 → 落点） | 涉及的页面/模块 | 产物文件 |
|---|---|---|---|
| 配置流 | 建数据源 → 建任务/动作 → 校验 → 启用注册路由 | 数据源 / 任务 / 动作链 | [config-flow.md](config-flow.md) |
| 动作链编译 | 配置落库 → 链一致性校验 → 按动作种类组合片段 → 注册 Camel 路由（含拆分 / 合并 / 还原） | 任务 / 动作链 | [chain-compile-flow.md](chain-compile-flow.md) |
| 北向同步 | 读客户 MySQL（增量水位）→ 字段映射 → 推送 MES HTTP | 任务 / 映射编辑器 | [northbound-sync.md](northbound-sync.md) |
| 南向同步 | MQ 订阅 → 字段映射 → 幂等 upsert 客户 MySQL | 任务 / 映射编辑器 | [southbound-sync.md](southbound-sync.md) |
| 同步 + 通知 | 读库 → 映射 → HTTP 推送 + MQ 发布 | 任务 / 动作链 | [notify-push.md](notify-push.md) |
| 触发流 | 手动执行 / 定时 / 外部 API 触发 | 任务 / 站点（API Key） | [trigger-flow.md](trigger-flow.md) |
| 字段映射 · 标量行 | 一行一个目标字段 → 行级转换 → JSONata 编译求值 | 映射编辑器（逐行） | [field-mapping/scalar-row-mapping.md](field-mapping/scalar-row-mapping.md) |
| 字段映射 · 数组层级 | 数组行 + 子行 + 外层引用 → `$map` 组规则 | 映射编辑器（数组行） | [field-mapping/array-mapping.md](field-mapping/array-mapping.md) |
| 字段映射 · 拼接片段 | 多段拼接 + 每段一个片段转换 → `&` 表达式 | 映射编辑器（CONCAT 行） | [field-mapping/concat-part-mapping.md](field-mapping/concat-part-mapping.md) |
| 字段映射 · 手写表达式 | `mode=EXPRESSION`：整段 JSONata，跳过规则树校验 | 映射编辑器（高级入口） | [field-mapping/expression-mode.md](field-mapping/expression-mode.md) |
| 字段映射 · 建议器 | 结构探测 → 归一化 → 相似度 → 历史复用 → 转换推断 | 后端 `/api/mapping-suggestions`（当前无 UI 入口） | [field-mapping/suggester.md](field-mapping/suggester.md) |
| 可靠性流 | 任务执行失败 → 重试退避 → 消息历史 → 断点重投 | 消息历史页 / 任务执行 | [reliability-flow.md](reliability-flow.md) |

> **字段映射那一组按映射形态拆成五份**（上面的五行），形态无关的共性——规则模型、编译与求值那一段、
> 「两档校验 + 保存那道门」的机制——在同一目录的总览里：[field-mapping/README.md](field-mapping/README.md)。
> 旧的单份文件 [transform-flow.md](transform-flow.md) 只留导航与旧编号（P6/P7/P8/P9、F1–F9）对照，不再有正文。

> 其余七个场景与字段映射五份共十二份产出文件均含业务场景与交互、Mermaid 时序图、逐环节代码走查
> （文件+行号）、测试环境、造数指导、建议表。

## 建议路由（走查后进入改进流程）

- 业务 / 产品建议 → `/grill-with-docs` 磨清并留纸面，**由用户拍板**；
- 原始问题 / 缺陷 → `/triage` 生成 agent-ready issue；
- 设计 / 架构建议 → `/domain-modeling` 记录为 ADR 候选（方向性变化补 Superseded/Amended）；
- 复杂故障 → `/diagnosing-bugs`；
- 文档偏差 → `doc-maint` 同步；
- 采纳需求 → Matt 主流程：`/to-spec` → `/to-tickets` → `/implement`（含 `/tdd`）→ `/code-review` → 提交。

## 读序

1. `CONTEXT.md` — 领域词汇；
2. `docs/user-guide.md` — 操作手册；
3. `docs/features/*/README.md` — 各功能现状 + 决策时间线；
4. `docs/adr/*.md` — 决策"为什么"。
