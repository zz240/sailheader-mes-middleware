# 文档地图

先在这张表里找到你要的那份，不要从目录树里翻。

## 三条路径

三条路径的第一站都是根 `README.md`——那是唯一一份讲「整体」的文档。

| 你是谁 | 按这个顺序读 | 读完能做什么 |
|---|---|---|
| 使用者（实施工程师） | 根 `README.md` → `user-guide.md` → `features/field-mapping/README.md` | 配通第一条链，并会配字段映射 |
| 运维 | `ops.md` → `flows/reliability-flow.md` → `agents/dev-environment.md` | 部署起来，判断一次失败该重跑还是该查对端 |
| 开发者 | 根 `README.md` 的「它分几层」→ 要动的功能页（`features/<功能>/`）→ 需要看代码时进 `flows/` 挑一条 | 先说清改动落在哪一层、会波及几处，再读代码 |

## 按你要做的事找

| 我要… | 看这份 | 内容 |
|---|---|---|
| 把网关跑起来、配通第一条链 | [user-guide.md](user-guide.md) | 操作手册：数据源 → 任务 → 动作 → 字段映射 |
| 部署、升级、现场排障 | [ops.md](ops.md) | 三个容器、Flyway 升级、排障四步、常见故障 |
| 半天内上手，走一遍真实任务 | [user-guide.md](user-guide.md) 的 §0–§7 | 从环境到触发的一条完整路径 |
| 改某个功能，先搞清现状 | [features/README.md](features/README.md) | 每个功能一页：怎么用 + 现状 + 时间线 |
| 顺着一条数据流读代码 | [flows/README.md](flows/README.md) | 业务 ↔ 代码走查，带类名与造数指导 |
| 知道当初为什么这么设计 | [adr/](adr/) | 决策记录，正文冻结，只追加状态 |
| 配对接场景、写实施规格 | [design/docking-scenarios-spec.md](design/docking-scenarios-spec.md) | 三条公共约束 + 场景规格 |
| 对着原型还原视觉 | [design/DESIGN-HANDOFF.md](design/DESIGN-HANDOFF.md) | 字段映射画布的视觉契约；ADR-0013 / ADR-0016 把它当依据引用 |
| 跑测试、装钩子、环境出问题 | [../README.md](../README.md)「快速运行」 + [agents/dev-environment.md](agents/dev-environment.md) | 命令在 README；本机坑在 agents |
| 写测试、做评审 | [agents/testing.md](agents/testing.md) 与 [../CODING_STANDARDS.md](../CODING_STANDARDS.md) | 测试规约与代码规范 |
| 让 agent 在这个仓库干活 | [../AGENTS.md](../AGENTS.md) | 入口、token 预算、交付流程 |
| 查外部事实、选型与实测依据 | [research/](research/) | 调研与实测，只读参考 |
| 看历史草案 | [archive/](archive/) | 过期内容，不再维护 |

## 一层一句话

| 目录 | 维护状态 | 写什么 |
|---|---|---|
| `user-guide.md` | 活 | 用户与实施视角的操作手册 |
| `features/` | 活 | 每个功能一页：怎么用 + 现状 + 时间线 |
| `flows/` | 活 | 数据怎么流、跑到哪些类、怎么造数 |
| `adr/` | 冻结 | 决策与取舍，只追加状态 |
| `design/` | 活 | 设计稿、对接场景规格 |
| `research/` | 只读 | 外部事实、选型与实测 |
| `archive/` | 冻结 | 过期草案 |
| `agents/` | 活 | 给 agent 的操作规程 |

## 约定

1. 活文档：`features/`、`flows/`、`design/`、`user-guide.md`。代码变了要跟着改。
2. 冻结文档：`adr/`、`archive/`。只追加状态与 Amended by 链接，不改正文。
3. **代码引用默认写符号，不写行号**：Java 写 `类名#方法名`（方法不确定就只写类名），前端写模块名（如 `useMappingDraft.ts`）。符号在仓库里必须唯一，不唯一的补一层目录。
4. **行号只留给入口**：每个流程最多两处，写成 `全路径:行号` 并在后面标「（入口）」。其余一律用符号。判据：要跳过去看一眼的用行号，要读一遍理解的用类名。
5. 提到另一份文档时给相对链接，不要只说「见走查文档」。索引靠链接，不靠记忆。
6. 新增位置：功能现状 → `features/<功能>/README.md`；代码走查 → `flows/<流程>.md`；决策 → `adr/NNNN-*.md`；给 agent 的规程 → `agents/`；只在一个功能里用的词 → 该功能页的「术语细节」，**不进 `GLOSSARY.md`**。
7. 引用 ADR 一律写 `ADR-00NN`（四位数字 + `ADR-` 前缀）。不要写 `adr/00NN`，也不要写裸 `0000`，两种写法脚本都查不到。
8. 每条 ADR 至少要被一个 `features/*/README.md` 引用；没人引用的就是孤儿 ADR，`tools/check-docs.ps1`（#149）会报。
9. **features 与 flows 不重复**：features 回答「怎么配、为什么这样」，不放代码引用；flows 回答「跑到哪些类、怎么造数验证」，不重复定义概念。同一句话要跟着代码变就放 flows，跟着功能行为变就放 features；两边都得跟着变，就改成一句指针。
10. **给使用者看的段落，只用界面上真出现的字**。项目内部的叫法（组节点、外层引用、标量规则）留到「实现细节」那一节。写不出界面对应的词，说明这个概念本身还没想清楚。

## 已知文档债

不记数字——数字一改就假。只记还在的事。

| 问题 | 现在的情况 | 处理 |
|---|---|---|
| 代码引用会随代码漂移 | 旧写法里有 `全路径:行号`，也有半路径和裸文件名 | 新写的一律用类名或模块名；旧的改到哪份顺手改哪份 |
| 文档引用无法自动校验 | 仓库没有检查脚本，CI 也不查文档 | 建 `tools/check-docs.ps1` 并接进 CI（#149） |
| flows 走查落后代码 | 走查写于 2026-09，之后代码又动过几轮 | 不批量返工；读到哪份发现对不上就顺手改 |
