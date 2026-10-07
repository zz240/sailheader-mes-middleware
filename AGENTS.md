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

要治的只有**碎**。**#140 那次实测：373 次工具调用摊在 249 步上（1.50 个/步），工具结果合计才 0.58MB、最大一条 43KB——上下文从头到尾都是平的（第 40 步 0.32MB → 第 249 步 0.58MB），账单 5703 万缓存读、5.47 元全是"步数多"读出来的，不是哪一步塞爆了。** 更早的实测：8320 次调用摊在 6717 步上（1.24 个/步）；1970 次 `edit` 只碰 158 个文件（`frontend/src/mapping/checks.ts` 一个文件 267 次）；52% 的 `read` 请求满 2000 行；pwsh 命令重复率 52%。七条：

- **一轮并发多个工具。** 互不依赖的读、grep、命令合进同一条消息；等子代理不用轮询，结束会通知。目标每步 ≥2.5 个工具——这一条单独就把步数和账单砍到 1/3。**#140 实测的缺口：146 步只发了 1 个工具、只有 20 步发了 3 个以上**，合掉一半单工具步就是省一半钱。
- **结构看不清楚时，先花一次调用把真相打出来，别拿"编译—跑测试"当探照灯。** 这条是 #140 最贵的一课：`RouteGenerator.java` 一个文件吃了 **70 次 `edit`（占全部 137 次的 51%）**、被整读 16 次，全花在试探 Camel 的 DSL 嵌套上（循环里套 split、`end()` 退几层）。同一轮里 `ChainConsistencyValidator` 是纯 Java 逻辑，9 次 `edit` 就收工。看不清模型形状（路由模型、状态机、递归结构）时先写**一次性探针**把结构 dump 出来——那 70 次里大约 40 次本可以省掉。
- **单会话封顶约 150 步。** 到线就把结论落进 issue 或文件，开新会话接着做。平方关系下拆成 N 段就是 ÷N；一句「实现 #xxx」跑几百步是账单最贵的形态（实测最贵会话 572 步、14.66 元；#140 是 249 步、5.47 元，超线 100 步）。
- **先 grep 定位、再读窗口。** `read` 一律带 `offset`/`limit`。最大文件 `frontend/src/mapping/checks.ts`（1953 行）、`MappingDraftValidationServiceTest`（1384 行）；编辑器自 #117 拆开后最大的是 `FieldMappingWorkbench.vue`（915 行，含样式块），其次是 `MappingRowList.vue`（835 行）。`RouteGenerator.java` 已按 #145 拆开（装配 400 行 + `StepLedger` / 各片段类，最大的 `StepLedger` 约 320 行），改动前先按类名 grep 定位到具体片段类，再按段读。**#140 实测整读占 31%**（64 次 `read` 里 20 次没带窗口）。
- **一个文件一次写完。** 大改用 `write` 落一次，`edit` 留给真正的定点修改；**同一个文件 `edit` 超过 10 次就停手，改成整段 `write` 重写**——#140 的 70 次就是没守这条。（同一轮里 `SplitActionExecutionTest.java` 也被改了 22 次。）
- **命令归一、输出限量。** 同一条测试命令一轮只跑一次；进程表、大 JSON、整份日志按需取几行。平时只跑相关测试类与 `node --experimental-strip-types frontend/src/mapping/checks.ts`，全量 `mvn test` 与 `tools/ui/*.cjs` 留到收尾各一次。
- **环境挂了就停手，别连续重试同一个结论。** `pwsh` 报 `0xC0000142`、`gh` 连不上、测试报"类文件不存在"这类环境故障，试一次确认就够；#140 开头为了确认 shell 死了连发 7 次，后面又因一次文件访问抖动多跑了一轮全量 `mvn test`——这些步数是白给的。

时间窗：闲时价为高峰价的一半（V4-Flash 闲时 1.5 / 4.5 / 0.05 元每百万 = 缓存命中 / 未命中 / 输出）。高峰是**北京时间 9-12 点与 14-18 点**，其余 17 小时皆闲时；长任务排在 12-14 点、18 点后与夜里。
