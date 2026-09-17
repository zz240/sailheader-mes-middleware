# ADR-0013: 控制台统一到原型那套视觉体系（令牌层 + 分三期）

## 状态

- **采纳**（2026-09-17，本次会话决议；范围由用户当场拍板为「全控制台」）
- 相关：ADR-0006（前端技术栈）、ADR-0011 / ADR-0012（字段映射编辑器的两轮改版）、
  `docs/features/config-ui/README.md`、`frontend/prototype/README.md`、
  `docs/research/ui-visual-refresh-scope.md`（工作量实测）
- 实现：第 1 期与第 2 期待开票；第 3 期 = #120（已开）
- 取代：无。本条**不修订** ADR-0011 / 0012 的任何决策——它们管的是数据形状与交互口径，与外观无关

## 背景

`docs/` 里从来没有一条关于界面外观的决定。12 条 ADR 全查过，最接近的只有 ADR-0006 定的技术栈
（Vue 3 + Element Plus）与 `docs/research/mapping-framework.md:147`「复用 Element Plus，无新依赖」——
**两条都只定技术选型，没有一句谈配色、字号、字体、圆角**。`CONTEXT.md` 157 行全是领域术语。

结果就是：界面上色从来没被当成一个决定做过，**Element Plus 的默认值（`#409eff` 蓝、14px、4px 圆角）
就是最终值**。而 `frontend/prototype/` 里那几支高保真原型（三轮迭代、合计 470KB）从没被 git 跟踪过，
是仓库里唯一的视觉参照物——一次 `git clean` 就会全部消失。

第 0 期要解决的是这两件事：**把参照物纳入版本控制，并把「要不要像原型」这个决定写下来。**

## 决策

1. **范围是全控制台**，不只是字段映射编辑器。统一的落地方式是**令牌层**：一份
   `frontend/src/styles/tokens.css` 同时定义这套视觉的自有令牌（`--bg/--surface/--fg/--muted/--border/--accent/
   --radius/--fs-*/--font-*`）**并覆盖 Element Plus 的 `--el-*` 令牌**，在 `main.ts` 里于
   `element-plus/dist/index.css` 之后引入。

   覆盖面 = 五个业务页面（数据源 / 任务与映射 / 消息历史 / 模板 / 站点）+ 字段映射编辑器抽屉 +
   全局件（`ElMessage` / `ElMessageBox` / 侧边栏 `el-menu`）。之所以选全控制台而不是只改编辑器：
   编辑器之外这五个页面自己的样式只有 **31 行**、真彩色 hex 只有 **9 处**，几乎全靠组件库默认外观撑着；
   只改编辑器会把「不一致」从一处搬到另一处。

2. **视觉契约以 `frontend/prototype/03-studio.html` 的 `:root` 为准**。它是这套令牌的最新版；其余三支
   同源（合计 0 处 hex），可一并参照。契约内容是十几个令牌 + 从它们派生的规则
   （`color-mix(in oklch, …)`），不是逐像素复刻。**改原型等于改契约**，要先改本条 ADR。

3. **分三期，先令牌后结构**：

   | 期 | 内容 | 动几个文件 | 票 |
   |---|---|---|---|
   | 0 | 本 ADR + 设计稿纳入版本控制 | 0（文档） | 本条 |
   | 1 | 令牌桥：`tokens.css` + `main.ts` 一行 + 全局 `body` | 2–3 | 待开 |
   | 2 | 编辑器这棵树换名（158 处 hex / 53 处字号 / 14 处圆角 / 5 处深度覆盖） | 13 | 待开 |
   | 3 | 结构对齐：试算页签的结论条 / 计量条 / 证据表 | 1–2 + 后端 | **#120** |

   **分期不是可选项**：第 1 期只能买到「主色/圆角/边框/文字/底色/菜单色一次改完」，
   **买不到「像原型」**——有实测为证（`docs/research/ui-visual-refresh-scope.md` §5、
   `.tmp-attach-fm/experiment/README.md`）：字号只改得到 Element Plus 侧 68 处 `var(--el-font-size-*)`，
   另有 170 处字面 `font-size:Npx` 够不着；`--el-font-family` 在 357KB 的 `dist/index.css` 里被 `var()`
   引用 **0 次**，换字体必须自己写一条全局 `body` 规则。

4. **不引新的 UI 依赖**（沿用 `mapping-framework.md:147`）。不换组件库、不引入 CSS 框架、不上 CSS-in-JS。
   这条与「像原型」不矛盾：原型是自绘，但生产侧**只取它的令牌与排版规则**，控件仍是 Element Plus。

5. **第 3 期不在「换外观」范围内。** 试算页签的结论条/计量条/证据表是**信息架构改造**，
   而且需要后端先扩 `MappingTrialResult`（逐字段状态），已按 #120 单独排期。第 1、2 期不碰它。

6. **三个硬约束**（实现时别踩，都是实测出来的）：

   - **`--el-color-primary-light-3/5/7/8/9` 是字面值**，必须和 `--el-color-primary` 一起显式覆盖，
     漏一个就留下蓝色描边/浅底（`--el-button-hover-bg-color: var(--el-color-primary-light-9)`、
     `.el-tabs__item.is-active{color: var(--el-color-primary)}`）。
   - **颜色不能只改 `:root` 就算完**：生产组件里有 **12 处手写的 `#409eff`**（`MappingRowList.vue:754/780`、
     `MappingSourcePanel.vue:304/322/323`、`styles/workbench-panel.css:25/40/41/45`），
     scoped 里的字面值压过令牌，换主色时不会跟着变。这些在第 2 期逐个换掉。
   - **下拉弹层要单独处理**：`el-select` / `el-option` / `el-autocomplete` 默认 teleport 到 body
     （全仓现在 0 处 `popper-class` 钩子），scoped 与 `:deep()` 都够不着；`ElMessage` / `ElMessageBox`
    渲染在 body 上。而 `el-drawer` / `el-dialog` 默认 **不** teleport（`appendToBody: false`），
    比直觉好压。这条是实测纠正过的常见误解。

7. **`wb-*` 类名不改名。** `tools/ui/` 下 11 个真机验收脚本（3746 行）与
   `docs/flows/field-mapping/*` 都把类名当锚点，改名只制造无收益的连锁修改。

## 结果

- **会得到**：全控制台同一套配色/字号/字体/圆角；一处改令牌、全站跟着变；设计稿有版本、有出处、
  有验收口径（能说清「对上了没有」）。
- **会失去**：Element Plus 默认外观（不可回退到「库本来的样子」除非删掉 `tokens.css` 的引入）；
  以及**第 2 期结束前会有一段过渡期**——那时编辑器的 384 行自绘样式已经换名、而五个业务页面还是
  组件库默认外观，看起来会比现在更不一致。这是分期必然的代价，不是回归。
- **已知不做**：控制台五个页面的**信息密度重排**（消息历史 25 列、任务页 32 个表单项）不在本期范围。
  dense utility 那套是给字段映射这类技术工具定的，表单型页面照搬未必合适——真要做需另开设计。
- **风险**：第 2 期会动 DOM 尺寸，而 `#106` 那套键盘逻辑对命中区域敏感，
  另有 2 处 JS 直接查 Element Plus 内部类名（`FieldMappingWorkbench.vue:679` 的 `.el-message-box`、
  `:806` 的 `.el-autocomplete-suggestion`）。第 2 期必须跑 `tools/ui/*.cjs` 全量真机验收。

## 锚点

- 工作量实测（本条的量化依据）：`docs/research/ui-visual-refresh-scope.md`
  —— 384 行 CSS 的样式面、158 处 hex/43 色、`:deep()` 4 处、`!important` 0 处、
  Element Plus `:root` 137 个可覆盖变量、170 处够不着的字面字号
- 视觉契约：`frontend/prototype/03-studio.html`（最新令牌）、`01-canvas.html`、`02-workbench.html`、
  `field-mapping-ledger.html`；说明见 `frontend/prototype/README.md`
- 设计说明：`docs/design/field-mapping-canvas-brief.md`（§2 设计方向、§10–§12 后两轮迭代）、
  `docs/design/DESIGN-HANDOFF.md`
- 一次真实渲染的三态对照（令牌前 / 只换令牌 / 令牌+换名）：
  `.tmp-attach-fm/experiment/`（三张 PNG + `states.json`；**未纳入版本控制**，属会话内证据，
  复现脚本同目录）
- 第 3 期：#120（试算结果的逐字段状态 + 结论/计量/证据三层）
