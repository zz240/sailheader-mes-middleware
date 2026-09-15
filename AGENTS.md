# AGENTS

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues of `zz240/sailheader-mes-middleware`, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles mapped to default labels in this repo's local tracker. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the repo root plus `docs/adr/` for decisions. See `docs/agents/domain.md`.

## Token budget

账单的大头是「每次调用重读整个上下文」（占八成以上），不是思考。四条习惯直接压这块：

- **换票先开新会话。** 一张票一个会话；新会话从 20k 上下文起步，连做三张会涨到 380k。
- **大文件先 grep 定位、再读窗口。** 本仓库单文件常见一千到两千行（`FieldMappingWorkbench.vue`、`MappingDraftValidationServiceTest`、`MappingSampleChecks`）。
- **命令输出一律加上限**（`Select-Object -First/-Last`）；进程表、大 JSON、整份测试日志按需取几行。
- **测试分层跑。** 平时只跑相关测试类与前端纯模块检查（`node --experimental-strip-types frontend/src/mapping/checks.ts`），全量 `mvn test` 与真机脚本 `tools/ui/*.cjs` 留到收尾各跑一次。
