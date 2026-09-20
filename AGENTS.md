# AGENTS

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues of `zz240/sailheader-mes-middleware`, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles mapped to default labels in this repo's local tracker. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the repo root plus `docs/adr/` for decisions. See `docs/agents/domain.md`.

### Vocabulary

技能自造的工程术语（接缝、深模块、前沿、曳光弹…）有中文对照表：`docs/agents/vocabulary.md`。用中文回话时，这些词首次出现要给一句白话解释并保留英文原名，同一个概念始终用表里的中文名。

## Token budget

账单几乎全是「每一步重读整段上下文」——实测缓存命中输入占成本约 70%，输出仅约 18%，且对工具调用次数是**平方**的（上下文随每个工具结果增长，每一步又重读全段）：

> 缓存读 ≈ 650 × 工具调用次数²

要治的只有**碎**。实测：8320 次工具调用摊在 6717 步上（1.24 个/步）；1970 次 `edit` 只碰 158 个文件（`frontend/src/mapping/checks.ts` 一个文件 267 次）；52% 的 `read` 请求满 2000 行；pwsh 命令重复率 52%。五条：

- **一轮并发多个工具。** 互不依赖的读、grep、命令合进同一条消息；等子代理不用轮询，结束会通知。目标每步 ≥2.5 个工具（当前 1.24）——这一条单独就把步数和账单砍到 1/3。
- **单会话封顶约 150 步。** 到线就把结论落进 issue 或文件，开新会话接着做。平方关系下拆成 N 段就是 ÷N；一句「实现 #xxx」跑几百步是账单最贵的形态（实测最贵会话 572 步、14.66 元）。
- **先 grep 定位、再读窗口。** `read` 一律带 `offset`/`limit`。最大文件 `frontend/src/mapping/checks.ts`（1953 行）、`MappingDraftValidationServiceTest`（1384 行）；编辑器自 #117 拆开后最大的是 `FieldMappingWorkbench.vue`（915 行，含样式块），其次是 `MappingRowList.vue`（835 行）。
- **一个文件一次写完。** 大改用 `write` 落一次，`edit` 留给真正的定点修改。
- **命令归一、输出限量。** 同一条测试命令一轮只跑一次；进程表、大 JSON、整份日志按需取几行。平时只跑相关测试类与 `node --experimental-strip-types frontend/src/mapping/checks.ts`，全量 `mvn test` 与 `tools/ui/*.cjs` 留到收尾各一次。

时间窗：闲时价为高峰价的一半（V4-Flash 闲时 1.5 / 4.5 / 0.05 元每百万 = 缓存命中 / 未命中 / 输出）。高峰是**北京时间 9-12 点与 14-18 点**，其余 17 小时皆闲时；长任务排在 12-14 点、18 点后与夜里。
