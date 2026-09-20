# ADR-0013: 控制台统一到原型那套视觉体系（令牌层 + 分三期）

## 状态

- **采纳**（2026-09-17，本次会话决议；范围由用户当场拍板为「全控制台」）
- 相关：ADR-0006（前端技术栈）、ADR-0011 / ADR-0012（字段映射编辑器的两轮改版）、
  `docs/features/config-ui/README.md`、`frontend/prototype/README.md`、
  `docs/research/ui-visual-refresh-scope.md`（工作量实测）
- 实现：第 1 期 = #121（**已落**）；第 2 期 = #122（**已落**）；第 3 期 = #120（已开）；
  第 4 期 = 消息历史页（2026-09-20 另开契约 **ADR-0016**）
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

3. **分期落地，先令牌后结构**（原「分三期」；2026-09-20 加了第 4 期，见下）：

   | 期 | 内容 | 动几个文件 | 票 |
   |---|---|---|---|
   | 0 | 本 ADR + 设计稿纳入版本控制 | 0（文档） | 本条 |
   | 1 | 令牌桥：`tokens.css` + `main.ts` 一行 + 全局 `body` | 2–3 | **#121** |
   | 2 | 编辑器这棵树换名（158 处 hex / 53 处字号 / 14 处圆角 / 5 处深度覆盖） | 13 | **#122** |
   | 3 | 结构对齐：试算页签的结论条 / 计量条 / 证据表 | 1–2 + 后端 | **#120** |
   | 4 | 结构对齐：消息历史页（双栏控制台 + 只读聚合接口 + 单步计时） | ~10 + 后端 | **#128**（契约 **ADR-0016**） |

   第 4 期是**另一个页面**的结构对齐（消息历史，设计稿 `frontend/prototype/04-message-history.html`）：
   它的信息架构、只读聚合接口与单步计时另立 `ADR-0016`，本条只登记进度；令牌口径仍旧以第 1、2 期为准。

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
- **会失去**：Element Plus 默认外观（不可回退到「库本来的样子」除非删掉 `tokens.css` 的引入）。
  <!-- 原文这里还写着「第 2 期结束前会有一段过渡期，五个业务页面还是组件库默认外观」——那说的其实是
       第 1 期结束时的样子，而第 1 期本就同时改了五个业务页面（令牌桥是全站的，ADR-0013 决策 1）。
       真实的分期落差是反过来的：第 1 期之后**编辑器**那棵树的字面值还压着令牌，看起来几乎没变
       （有实测：`.tmp-attach-fm/experiment/states.json`），第 2 期（#122）才把它换过来。
       原句不改，照 ADR 惯例只作注，免得抹掉当时的判断。 -->
- **已知不做**：控制台五个页面的**信息密度重排**（消息历史 25 列、任务页 32 个表单项）不在本期范围。
  dense utility 那套是给字段映射这类技术工具定的，表单型页面照搬未必合适——真要做需另开设计。
- **风险**：第 2 期会动 DOM 尺寸，而 `#106` 那套键盘逻辑对命中区域敏感，
  另有 2 处 JS 直接查 Element Plus 内部类名（`FieldMappingWorkbench.vue:679` 的 `.el-message-box`、
  `:806` 的 `.el-autocomplete-suggestion`）。第 2 期必须跑 `tools/ui/*.cjs` 全量真机验收。

## 第 2 期落地（#122）

- **换名清单**：13 个文件（11 个含 `<style>` 的 `.vue` + `styles/workbench-panel.css` +
  `styles/workbench-issues.css`）里的字面值全部换成令牌引用——实际换掉 **199 处手写 hex + 61 处字号
  + 19 处圆角 + 13 处等宽字体族**（票上写的 158/53/14 是评估那天 `92233d9` 的数，差额是 #120 试算
  那三段新加的样式）。改完这 13 个文件 grep 不到任何手写 hex（票号 `#NNN` 不算）。
- **新增两个令牌**（都落在 `tokens.css`，组件里不另造）：`--success` / `--success-soft` / `--success-ink`
  一支（原型只有 warn / danger 两支信号色，成功态原先散着四个 tailwind 绿），以及 `--radius-sm`(6px)
  ——第 2 期「圆角三档」里的最小那一档。
- **两个硬点**：① 决策 6 点名的 12 处手写 `#409eff` 逐个换成 `var(--accent)`；② 下拉弹层**不改**——
  弹层的颜色/圆角第 1 期起就走 `:root`，剩下那点字号差是 Element Plus 自己写在元素层上的，
  加 `popper-class` 只会把新的字面值搬进编辑器这棵树，与「令牌层是唯一来源」相抵。
- **验收**：`tools/ui/` 11 个脚本全跑。`keyboard-narrow-screen-106` 的八个宽度与键盘走行/换转换/选来源
  全通过；该脚本里「点行」那一步的取样点做了调整（原先点行的**几何中心**，字号归一之后中心正好落在
  「换转换」小块上）——改的是取样点，断言一个字没动。另有两个脚本在**改动前的基线上同样复现**的
  环境问题上不过。完整记录见 #122 的评论。

## 锚点

- 工作量实测（本条的量化依据）：`docs/research/ui-visual-refresh-scope.md`
  —— 384 行 CSS 的样式面、158 处 hex/43 色、`:deep()` 4 处、`!important` 0 处、
  Element Plus `:root` 137 个可覆盖变量、170 处够不着的字面字号
- 视觉契约：`frontend/prototype/03-studio.html`（最新令牌）、`01-canvas.html`、`02-workbench.html`、
  `field-mapping-ledger.html`；说明见 `frontend/prototype/README.md`
- 消息历史页的契约：`docs/adr/0016-message-history-console.md` + `frontend/prototype/04-message-history.html`
- 设计说明：`docs/design/field-mapping-canvas-brief.md`（§2 设计方向、§10–§12 后两轮迭代）、
  `docs/design/DESIGN-HANDOFF.md`
- 一次真实渲染的三态对照（令牌前 / 只换令牌 / 令牌+换名）：
  `.tmp-attach-fm/experiment/`（三张 PNG + `states.json`；**未纳入版本控制**，属会话内证据，
  复现脚本同目录）
- 第 3 期：#120（试算结果的逐字段状态 + 结论/计量/证据三层）
