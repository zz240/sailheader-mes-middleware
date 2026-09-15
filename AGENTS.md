# AGENTS

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues of `zz240/sailheader-mes-middleware`, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles mapped to default labels in this repo's local tracker. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the repo root plus `docs/adr/` for decisions. See `docs/agents/domain.md`.

## Token budget

账单的大头是「每次调用重读整个上下文」（占八成以上），不是思考。五条习惯直接压这块：

- **换票先开新会话。** 一张票一个会话；新会话从 20k 上下文起步，连做三张会涨到 380k。
- **一轮多发几个工具。** 相互独立的读与命令合并进同一条消息（本仓库一次典型会话里 607 个工具调用摊在 564 次模型调用上，约等于每轮都重读一遍上下文）；等子代理不用轮询，结束会通知。
- **大文件先 grep 定位、再读窗口。** `frontend/src/mapping/checks.ts`（1610 行）现在是全仓库最大的文件，其次 `MappingDraftValidationServiceTest`（1272 行）；编辑器那一摞自 #117 拆开之后最大的是 `MappingRowList.vue`（约 755 行），`FieldMappingWorkbench.vue` 只剩 655 行的装配层。
- **改一个文件能一次写全就一次写。** 大改用 `write` 落一次，别拆成十几次 `edit`。
- **命令输出加上限、测试分层跑。** 进程表、大 JSON、整份日志按需取几行；平时只跑相关测试类与 `node --experimental-strip-types frontend/src/mapping/checks.ts`，全量 `mvn test` 与 `tools/ui/*.cjs` 留到收尾各一次。
