# AGENTS

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues of `zz240/sailheader-mes-middleware`, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles mapped to default labels in this repo's local tracker. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` at the repo root plus `docs/adr/` for decisions. See `docs/agents/domain.md`.

### Vocabulary

技能自造的工程术语（接缝、深模块、前沿、曳光弹…）有中文对照表：`docs/agents/vocabulary.md`。用中文回话时，这些词首次出现要给一句白话解释并保留英文原名，同一个概念始终用表里的中文名。

### 代码规范

`CODING_STANDARDS.md`：评审按它判，能自动判的已经落到 `.githooks/commit-msg` 与 CI。写代码前读「后端 / 前端 / 文档」三节。

### 环境与命令

怎么跑测试、本机已知故障（`pwsh` 报 `0xC0000142`、沙箱拒绝 fork、BOM 混进提交信息）：`docs/agents/dev-environment.md`。**新会话开工前先看这一节，别重新试错。**

## Token budget

账单几乎全是「每一步重读整段上下文」：成本随**步数**平方增长，不是某一步塞爆（实测数字见 `docs/agents/token-budget.md`）。四条动作句：

- **一轮并发多个工具。** 互不依赖的读、grep、命令合进同一条消息；等子代理不用轮询，结束会通知。目标每步 ≥2.5 个工具。
- **先定位、再读窗口。** 先 grep 找类名/字段，再 `read` 带 `offset`/`limit`。看不清结构（路由模型、状态机、递归嵌套）时，先写一次性探针把结构打出来，别拿"编译—跑测试"当探照灯——#140 里 `RouteGenerator.java` 吃了 70 次 `edit`，就是少了这一步。
- **一个文件一次写完。** 大改用 `write` 一次落，`edit` 留给真正的定点改；同一个文件 `edit` 超过 10 次就停手，改成整段重写。
- **命令归一、输出限量。** 同一条测试一轮只跑一次；进程表、大 JSON、整份日志按需取几行。平时跑 `pwsh -File tools/run-tests.ps1 <类名>`，全量测试与 `tools/ui/*.cjs` 留到收尾各一次。

环境挂了先看 `docs/agents/dev-environment.md`，**试一次确认就停手**，别连续重试同一个结论。

## 交付

一个票做完，**一次给出完整收尾清单**：合并 → 推送 → 删分支 → 等 CI 绿 → 关票 → 更新记忆。等一句确认后一次做完，不要一步一问——每个独立轮次都是一次整段上下文重读（#148 的收尾被拆成 4 轮，就是这么付的钱）。

时间窗：闲时价为高峰价的一半（V4-Flash 闲时 1.5 / 4.5 / 0.05 元每百万 = 缓存命中 / 未命中 / 输出）。高峰是**北京时间 9-12 点与 14-18 点**，其余 17 小时皆闲时；长任务排在 12-14 点、18 点后与夜里。
