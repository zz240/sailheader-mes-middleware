# 文档地图

先在这张表里找到你要的那份，不要从目录树里翻。

## 按你要做的事找

| 我要… | 看这份 | 内容 |
|---|---|---|
| 把网关跑起来、配通第一条链 | [user-guide.md](user-guide.md) | 操作手册：数据源 → 任务 → 动作 → 字段映射 |
| 半天内上手，走一遍真实任务 | [evaluation/15min-walkthrough.md](evaluation/15min-walkthrough.md) | 新实施工程师的 15 分钟走查 |
| 改某个功能，先搞清现状 | [features/README.md](features/README.md) | 每个功能一页：现状 + 决策时间线 |
| 顺着一条数据流读代码 | [flows/README.md](flows/README.md) | 业务 ↔ 代码走查，带 file:line |
| 知道当初为什么这么设计 | [adr/](adr/) | 17 份决策记录，正文冻结 |
| 配对接场景、写实施规格 | [design/docking-scenarios-spec.md](design/docking-scenarios-spec.md) | 三条公共约束 + 场景规格 |
| 跑测试、装钩子、环境出问题 | [agents/dev-environment.md](agents/dev-environment.md) | 本机坑与命令 |
| 写测试、做评审 | [agents/testing.md](agents/testing.md) 与 [../CODING_STANDARDS.md](../CODING_STANDARDS.md) | 测试规约与代码规范 |
| 让 agent 在这个仓库干活 | [../AGENTS.md](../AGENTS.md) | 入口、token 预算、交付流程 |
| 查外部事实与选型依据 | [research/](research/) | 调研报告，只读参考 |
| 看历史草案 | [archive/](archive/) | 过期内容，不再维护 |

## 一层一句话

| 目录 | 份数 | 维护状态 | 写什么 |
|---|---|---|---|
| `user-guide.md` | 1 | 活 | 用户与实施视角的操作手册 |
| `features/` | 8 | 活 | 某功能是什么、为什么这样 |
| `flows/` | 15 | 活 | 数据怎么流、代码在哪一步 |
| `adr/` | 17 | 冻结 | 决策与取舍，只追加状态 |
| `design/` | 4 | 活 | 设计稿、对接场景规格 |
| `research/` | 4 | 只读 | 外部事实与选型调研 |
| `evaluation/` | 1 | 活 | 上手走查脚本 |
| `archive/` | 2 | 冻结 | 过期草案 |
| `agents/` | 7 | 活 | 给 agent 的操作规程 |

## 约定

1. 活文档：`features/`、`flows/`、`design/`、`user-guide.md`。代码变了要跟着改。
2. 冻结文档：`adr/`、`archive/`。只追加状态与 Amended by 链接，不改正文。
3. 代码引用一律写 `相对仓库根的路径:行号`。例如 `spike/src/main/java/com/example/edgegateway/service/ReadSqlBatcher.java:122`。
4. 不要写 `picker.ts:61` 这种裸文件名。人和机器都定位不到唯一文件。
5. 提到另一份文档时给相对链接，不要只说「见走查文档」。索引靠链接，不靠记忆。
6. 新增位置：功能现状 → `features/<功能>/README.md`；代码走查 → `flows/<流程>.md`；决策 → `adr/NNNN-*.md`；给 agent 的规程 → `agents/`。

## 已知文档债

| 问题 | 证据 | 处理 |
|---|---|---|
| 字段映射走查的引用大面积失效 | `flows/field-mapping/array-mapping.md` 24 个全路径引用中 9 个文件已不存在；另有 150 处裸文件名 | 引用统一为全路径，逐条核对 |
| 字段映射 5 份走查落后代码 | 各落后 14 次提交 | 与代码对齐一次 |
| 文档引用无法自动校验 | 仓库没有检查脚本，CI 不查文档 | 建 `tools/check-docs.ps1` 并接进 CI |
| 一份交接单没挂到任何索引 | `design/DESIGN-HANDOFF.md` 全仓零入链 | 确认是否归档到 `archive/` |
