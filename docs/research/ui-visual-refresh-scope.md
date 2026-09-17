# 界面视觉改造（字段映射编辑器 → 原型视觉体系）工作量评估

> 只读评估，未修改仓库里任何现有文件。本次唯一的写入是本文件。
> 评估基线：HEAD = `92233d9`；工作区已有改动/未跟踪项（与本次无关，仅作说明）：
> `git status --porcelain` 显示 `M AGENTS.md`、`M docs/flows/README.md`、`M docs/flows/transform-flow.md`、
> `M spike/.../RuleMappingSuggesterEvalTest.java`，以及未跟踪的 `.scratch/`、`.tmp-attach-fm/`、`frontend/prototype/`、
> `docs/agents/vocabulary.md`、`docs/flows/field-mapping/`、`tools/JUnitRunner.java`、`tools/run-tests.ps1`。
> element-plus 实装版本 **2.14.4**（`frontend/node_modules/element-plus/package.json`；`frontend/package.json` 写的是 `^2.9.3`）。

---

## 一句话结论

**要换的东西不多，但「只改 `:root` 变量」这条最省事的路走不通。** 编辑器这棵树 28 个文件、8588 行（占 `frontend/src` 全部行的 83.7%），
可真正的样式面只有 **300 行 `<style>`（13 个 `.vue`）+ 84 行独立 `.css` = 384 行 CSS + 86 个 `wb-*` 类名 + 158 处手写 hex（去重 43 色）+ 53 处手写字号 + 14 处圆角**；
Element Plus 侧 `:root` 里确实有 137 个可覆盖的 `--el-*`，主色/圆角/边框色/文字色能靠它一次改掉，
但**字号只能改到 68 处 `var(--el-font-size-*)` 引用处**（该文件里另有 **170 处**字面 `font-size:Npx` 够不着），
**`--el-font-family` 在整个 `dist/index.css` 里 0 处被 `var()` 引用**，而全仓库（`index.html` + `frontend/src`）**没有任何一条全局 `font-family`**——
所以「换字体」必须另写一条全局规则。分期建议：**一期令牌桥（2–3 个文件）改动极小但只买到主色/圆角/等宽字体；真正让编辑器像原型的是二期（13 个文件，换掉 158 处 hex + 53 处字号 + 14 处圆角）；三期（原型那套结论条/计量条/证据表）不是 CSS 能做的，要后端扩返回。**

---

## 0. 复查口径（所有数字都按这里的方法数出来；含一个必须知道的计数陷阱）

| 项 | 命令 / 方法 |
|---|---|
| **整文件行数（本文统一口径）** | **LF 数**（等价 `wc -l`）：`(Get-Content <f> -Raw) -split "\`n"` 或 Node `(fs.readFileSync(f,'utf8').match(/\n/g)\|\|[]).length` |
| 目录分组行数 | 上面对每个文件计数后按目录求和 |
| `<style>` 块行数 | 取 `<style ...>` 所在行与配对的 `</style>` 所在行的**行号差减一**（不含这两个标签行本身） |
| hex / 字号 / 圆角 / `el-*` / `:deep` / `wb-` | 自写只读 Node 脚本，放在 `%TEMP%`（**不在仓库内**）：`fm-scope-analyze{,2,3,4}.cjs`、`fm-scope-final.cjs`、`fm-counts.cjs`。核心正则在下面各节逐条写出，可照抄重跑 |
| Element Plus 令牌 | 直接读 `frontend/node_modules/element-plus/dist/index.css`（**单行、无结尾换行、357,817 字节**），正则 + 括号配平切出 `:root` 块 |
| EP 组件的 prop 默认值 | 读 `frontend/node_modules/element-plus/es/components/*/src/*.mjs`（`appendToBody` / `teleported` 的 `default`），**不要**按记忆或旧文档猜 |
| 精确到行号的清单 | `Select-String`（注意它**没有** `-Recurse` 参数，要先 `Get-ChildItem -Recurse`）或 Node 逐行 findIndex |

### 陷阱 1：票号会被 hex 正则命中

本仓库把票号写成 `#113`、`#119` 这种形式，它们**能通过 hex 正则**（`#[0-9a-fA-F]{3,8}\b` 会命中 `#113`——纯数字三/四位是合法 CSS 色值）。
不排除时 `frontend/src` 里数出 **443 处 / 59 种**；把纯数字的 `#NNN`（`/^#[0-9]{3,4}$/`）剔掉后才是真颜色：**158 处 / 43 种**。
被误计的票号有 **285 处 / 16 个值**（`#100 #101 #102 #103 #104 #105 #106 #107 #111 #112 #113 #115 #116 #117 #118 #119`）。
下文 hex 数字一律是**已剔除票号**的口径。

### 陷阱 2（本次踩到，且会污染行数）：`Get-Content` 在这台机器上会**少数行**

这台机器上 `Get-Content` 的默认解码不是 UTF-8（输出中文是乱码，例如 `App.vue` 的 `边缘数据网关` 显示成 `杈圭紭鏁版嵁缃戝叧`）。
UTF-8 中文字符的尾字节落在 GBK 的**前导字节**区间，于是当一行的最后一个字符是中文时，**它后面的换行字节被当成双字节字符的一部分吃掉**，两行被并成一行。
实测（同一份文件三种数法）：

| 文件 | LF 字节数（= 真实行数） | `(Get-Content).Count` | `Get-Content \| Measure-Object -Line` |
|---|---|---|---|
| `frontend/src/components/MappingExpressionEditor.vue` | **113** | 105 | 96 |
| `frontend/src/styles/workbench-panel.css` | **59** | 51 | 50 |
| `docs/adr/0011-field-mapping-editor-rework.md` | **132** | 53 | 45 |
| `docs/features/field-mapping/README.md` | **157** | 59 | 52 |

**本文所有行数都按 LF 数（第一列）**。顺带说明：`docs/flows/field-mapping/README.md:159` 那条 N4 自己就记过
「`Get-Content <f> | Measure-Object -Line` 会漏掉空行，同一份文件它数出 1547 / 1118 / 696」——
这次实测进一步表明**连 `.Count` 都会漏**（漏的是「被吞掉的换行」，不是空行），写口径时必须写清用哪种数法。

---

## 1. 映射编辑器这棵树的范围

### 数字

| 目录 | 文件数 | 总行数（LF） | 是否编辑器专用 |
|---|---|---|---|
| `frontend/src/components/*.vue` | 10 | 2714 | **全部是**（文件名全为 `Mapping*` / `FieldMappingWorkbench`） |
| `frontend/src/mapping/*.ts` | 16 | 5790 | **全部是** |
| `frontend/src/styles/*.css` | 2 | 84 | **全部是** |
| 合计（编辑器树） | **28** | **8588** | — |
| `frontend/src` 全部（`*.vue,*.ts,*.css`） | 39 | 10264 | 编辑器树占 **83.7%** 的行 |

逐文件行数（`components` 按行数降序；括号内是 `<style>` 块行数）：

| 文件 | 行 | `<style>` 行 |
|---|---|---|
| `components/FieldMappingWorkbench.vue` | 915 | 52（另有 2 个 `<style src>` 空块） |
| `components/MappingRowList.vue` | 835 | 93 |
| `components/MappingSourcePanel.vue` | 340 | 53 |
| `components/MappingTrialPanel.vue` | 154 | 34 |
| `components/MappingExpressionEditor.vue` | 113 | 22 |
| `components/MappingEnumParams.vue` | 111 | 0 |
| `components/MappingDateParams.vue` | 78 | 0 |
| `components/MappingIssuesPanel.vue` | 67 | 7 |
| `components/MappingPasteSampleDialog.vue` | 67 | 8 |
| `components/MappingIndexParams.vue` | 34 | 0 |

`mapping/*.ts`（16 个）：`checks.ts` 1953、`fast-config.ts` 691、`useWorkbenchState.ts` 445、`source-picker.ts` 358、
`editor-model.ts` 340、`transforms.ts` 337、`useMappingDraft.ts` 333、`rows.ts` 268、`parts.ts` 190、`draft.ts` 177、
`sample.ts` 176、`trial.ts` 156、`keyboard.ts` 133、`labels.ts` 87、`useMappingTrial.ts` 80、`expression.ts` 66。

`styles/*.css`：`workbench-panel.css` 59、`workbench-issues.css` 25。

### 归属证据

- 唯一入口：`frontend/src/views/JobsView.vue:245` `<FieldMappingWorkbench`，`:265` `import FieldMappingWorkbench from '../components/FieldMappingWorkbench.vue'`；
  全仓 grep `FieldMappingWorkbench` 的其它命中都在注释里。
- `styles/` 两个文件是**全局（非 scoped）**样式表，由编辑器以 `<style src=...>` 引入：
  `FieldMappingWorkbench.vue:913` `<style src="../styles/workbench-panel.css"></style>`、`:915` `<style src="../styles/workbench-issues.css"></style>`。
- `components/` 下 10 个文件没有任何一个是通用组件（`views/` 之外没有引用点）。

### 解读

8588 行里**绝大部分与外观无关**：光 `mapping/checks.ts` 一个文件就 1953 行纯逻辑（它的 47 处「hex」**全是票号**，不是颜色）。
真正要动的样式面 = **300 行 `<style>`（13 个 `.vue`）+ 84 行独立 CSS = 384 行**，
加上 86 个 `wb-*` 类名（`wb-` 出现 338 次，去重 86 个）。
**范围问题因此不是「要读多少代码」，而是「要改多少行 CSS」——不到 400 行。**

---

## 2. 整个前端应用的范围

### 数字

| 文件 | 行（LF） | `<style>` 行 | 真彩色 hex 处数 |
|---|---|---|---|
| `views/JobsView.vue` | 585 | 4 | 2 |
| `views/HistoryView.vue` | 343 | 14 | 4 |
| `views/DataSourcesView.vue` | 276 | 5 | 2 |
| `views/TemplatesView.vue` | 131 | 3 | 0 |
| `views/SitesView.vue` | 66 | 2 | 0 |
| `App.vue` | 23 | 3 | 1（`.aside` 的 `#e5e7eb`） |
| `router/index.ts` | 15 | — | 0 |
| （其余）`types.ts` 188、`api/client.ts` 41、`main.ts` 7、`vite-env.d.ts` 1 | 237 | — | 0 |

视图与路由（`router/index.ts:7-11`）：`/data-sources` → `DataSourcesView.vue`、`/jobs` → `JobsView.vue`、
`/history` → `HistoryView.vue`、`/templates` → `TemplatesView.vue`、`/sites` → `SitesView.vue`，`/` 重定向到 `/data-sources`。
`App.vue` 是外壳：`el-container` + `el-aside width="200px"` + `el-menu`（5 个 `el-menu-item`）+ `el-main`，整个文件只有 23 行。

### 解读

编辑器之外的整个应用，**样式面总共 31 行 scoped `<style>`、9 处真彩色 hex**——五个页面几乎完全靠 Element Plus 默认外观撑着。
「全站换肤」的成本因此几乎为 0（只要有令牌桥），**全部工作量集中在编辑器这一棵树上**；
反过来说，令牌桥一旦上到 `:root`，五个业务页面的观感会被一起改掉（见 §4：视图侧占 298/359 处 `el-*`）——这是范围问题，不是技术问题。

---

## 3. 手写样式与颜色的量

### 3.1 每个 `.vue` 的 `<style>` 块行数（共 13 个文件 / 300 行）

| 文件 | 行 | 文件 | 行 |
|---|---|---|---|
| `MappingRowList.vue` | 93 | `MappingPasteSampleDialog.vue` | 8 |
| `MappingSourcePanel.vue` | 53 | `MappingIssuesPanel.vue` | 7 |
| `FieldMappingWorkbench.vue` | 52 | `DataSourcesView.vue` | 5 |
| `MappingTrialPanel.vue` | 34 | `JobsView.vue` | 4 |
| `MappingExpressionEditor.vue` | 22 | `App.vue` | 3 |
| `HistoryView.vue` | 14 | `TemplatesView.vue` | 3 |
| — | — | `SitesView.vue` | 2 |

**3 个 `.vue` 完全没有 `<style>`**：`MappingDateParams.vue`、`MappingEnumParams.vue`、`MappingIndexParams.vue`——
它们的样式基元在 `styles/workbench-panel.css` 里（`docs/flows/field-mapping/concat-part-mapping.md:158-159` 记了这条理由）。
除 `FieldMappingWorkbench.vue:913/915` 那两个 `<style src>` 之外，其余全部是 `<style scoped>`。

### 3.2 手写 hex：158 处 / 43 种

正则 `#[0-9a-fA-F]{3,8}\b`，剔除 `/^#[0-9]{3,4}$/`（票号，见 §0 陷阱 1）。

按选择器归属分桶（`.css` 文件整体当 CSS 解析；`.vue` 只解析 `<style>` 内容；**选择器里含 `wb-` 的算「映射树类名」，其余算「其它」**）：

| 归属 | 出现次数 | 去重 |
|---|---|---|
| `wb-*` 映射树类名 | **136** | **37** |
| 其它选择器 | **22** | **15** |
| （无选择器的 `@media` 外层：解析时被重计 1 次） | 1 | 1 |
| 合计 | **158** | **43** |

- `wb-*` 侧（136 处）：`MappingRowList.vue` 52、`MappingSourcePanel.vue` 26、`workbench-panel.css` 23、
  `FieldMappingWorkbench.vue` 14、`workbench-issues.css` 10、`MappingIssuesPanel.vue` 5、
  `MappingPasteSampleDialog.vue` 5、`MappingExpressionEditor.vue` 1。
- 「其它」侧（22 处）：`MappingTrialPanel.vue` 13、`HistoryView.vue` 4、`DataSourcesView.vue` 2、`JobsView.vue` 2、`App.vue` 1。
  选择器样本：`.trial-where / .trial-premise / .trial-col-head / .trial-text / .trial-empty / .trial-ok / .trial-bad /
  .trial-attribution* / .trial-failure* / .msg / .hint / .muted / .k / .err / .issue.error / .issue.warn / .aside / .mono`。
- 逐值分布（43 个值全列）：

  | 次数 | 值 |
  |---|---|
  | 19 | `#9ca3af` |
  | 12 | `#6b7280`、`#e5e7eb`、**`#409eff`（EP 默认主色）** |
  | 11 | `#b45309` |
  | 9 | `#b91c1c` |
  | 6 | `#fff` |
  | 5 | `#374151`、`#fffbeb`、`#fde68a`、`#ecf5ff` |
  | 4 | `#fecaca`、`#d1d5db`、`#111827` |
  | 3 | `#f3f4f6`、`#f8fafc`、`#fef2f2`、`#15803d` |
  | 2 | `#92400e`、`#1d4ed8`、`#0369a1`、`#e0f2fe`、`#c7d2fe`、`#3730a3`、`#909399`、`#eef2ff` |
  | 1 | `#c0392b`、`#bae6fd`、`#2563eb`、`#606266`、`#7f1d1d`、`#dc2626`、`#f0f9ff`、`#4b5563`、`#ef4444`、`#fcfcfd`、`#fafafa`、`#f9fafb`、`#dbeafe`、`#7c3aed`、`#f0fdf4`、`#d97706`、`#bbf7d0` |

### 3.3 `font-size: Npx` 分布（`frontend/src` 全部 `.vue/.ts/.css`）

正则 `font-size\s*:\s*([^;}"'\n]+)`，**53 处 / 6 个取值**：

| 次数 | 值 |
|---|---|
| 29 | `12px` |
| 19 | `11px` |
| 2 | `13px` |
| 1 | `15px`（`App.vue` 的 `.logo`） |
| 1 | `14px` |
| 1 | `10px`（`workbench-panel.css` 的 `.wb-opbtn small`） |

（只在 `<style>` 块里数得到 41 处，差额 12 处在两个独立 `.css` 里。）

### 3.4 `border-radius` 分布

正则 `border-radius\s*:\s*([^;}"'\n]+)`，**14 处 / 3 个取值**：

| 次数 | 值 |
|---|---|
| 9 | `4px` |
| 3 | `6px` |
| 2 | `3px` |

### 解读

- 字号不是「有很多档」，而是**只有两档在真正干活**：`12px + 11px = 48/53 = 90.6%`。
  原型是 `--fs-body: 13.5px` / `--fs-meta: 11.5px`——**同一套「两级节奏」，只是数值差半档**。字号不是散落，是没归位。
- 圆角同理：`4px` 占 9/14，原型是 `--radius: 8px` / `--radius-lg: 12px`——**差一档，不是两套体系**。
- 颜色则**真的散**：43 个值里 **15 个是中性灰/白**（`#111827 #374151 #4b5563 #606266 #6b7280 #909399 #9ca3af #d1d5db #e5e7eb #f3f4f6 #f8fafc #f9fafb #fafafa #fcfcfd #fff`），
  另外 **28 个**是状态色与强调色的各种手调深浅。尤其注意 **EP 的默认主色 `#409eff` 被手写进了编辑器自己的 9 条规则（12 处）**：
  `MappingRowList.vue:754 / :780`、`MappingSourcePanel.vue:304 / :322 / :323`、`workbench-panel.css:25 / :40 / :41 / :45`
  （`.wb-row.selected`、`.wb-chip.chip-op-empty:hover`、`.wb-filterbtn.current`、`.wb-chip:hover`、`.wb-opbtn.current`、`.wb-opbtn.kb-current`…）
  ——换主色时这些地方**不会跟着令牌变**，必须逐个换。
- 原型 `reference-field-mapping-studio.html`（1891 行）的 `:root`（第 10 行起）**一个 hex 都没有**，`color-mix(in oklch, …)` 用了 22 次；
  另一支 `field-mapping-ledger.html`（1823 行）同样 0 hex / 25 次 `color-mix`。→ 这套视觉体系从设计上就是「十几个令牌 + 派生」，
  与生产侧 43 个孤立色值是**结构差异**，不是审美差异。

---

## 4. Element Plus 的使用面

`main.ts:6` 是**全量注册**（`import ElementPlus from 'element-plus'` + `createApp(App).use(ElementPlus)`），所以下面每个组件在所有页面都可用。

正则 `<(el-[a-z0-9-]+)`。**34 个不同标签、359 次出现**；按区域分：
**编辑器组件 61 次（9 个文件）** / **views + App.vue 298 次（6 个文件）**
（`JobsView` 135、`HistoryView` 66、`DataSourcesView` 54、`TemplatesView` 21、`SitesView` 13、`App.vue` 9）。

| 组件 | 次数 | 用在哪些文件（次数） |
|---|---|---|
| `el-button` | 66 | JobsView 17、MappingRowList 11、DataSourcesView 9、HistoryView 8、TemplatesView 7、FieldMappingWorkbench 4、MappingEnumParams 3、MappingPasteSampleDialog 2、MappingSourcePanel 2、MappingExpressionEditor 1、MappingTrialPanel 1、SitesView 1 |
| `el-table-column` | 60 | HistoryView 25、JobsView 16、DataSourcesView 9、SitesView 6、TemplatesView 4 |
| `el-form-item` | 45 | JobsView 32、DataSourcesView 11、TemplatesView 2 |
| `el-input` | 39 | JobsView 16、DataSourcesView 11、MappingRowList 3、MappingEnumParams 2、TemplatesView 2、MappingDateParams 1、MappingExpressionEditor 1、MappingPasteSampleDialog 1、MappingSourcePanel 1、HistoryView 1 |
| `el-option` | 31 | JobsView 21、HistoryView 4、DataSourcesView 3、MappingRowList 2、FieldMappingWorkbench 1 |
| `el-select` | 16 | JobsView 10、MappingRowList 2、HistoryView 2、DataSourcesView 1、FieldMappingWorkbench 1 |
| `el-tooltip` | 11 | MappingRowList 9、FieldMappingWorkbench 2 |
| `el-dialog` | 10 | JobsView 3、DataSourcesView 2、TemplatesView 2、MappingPasteSampleDialog 1、HistoryView 1、SitesView 1 |
| `el-table` | 10 | HistoryView 3、JobsView 3、DataSourcesView 2、SitesView 1、TemplatesView 1 |
| `el-descriptions-item` | 10 | HistoryView 10 |
| `el-input-number` | 9 | JobsView 6、DataSourcesView 2、MappingIndexParams 1 |
| `el-tag` | 8 | HistoryView 4、JobsView 3、SitesView 1 |
| `el-alert` | 6 | JobsView 2、SitesView 2、FieldMappingWorkbench 1、MappingExpressionEditor 1 |
| `el-menu-item` | 5 | App.vue 5 |
| `el-card` | 5 | DataSourcesView / HistoryView / JobsView / SitesView / TemplatesView 各 1 |
| `el-form` | 4 | JobsView 2、DataSourcesView 1、TemplatesView 1 |
| `el-tab-pane` | 3 | FieldMappingWorkbench 3 |
| `el-collapse` / `el-collapse-item` | 2 / 2 | DataSourcesView 1、HistoryView 1 |
| `el-radio-button` | 2 | HistoryView 2 |
| `el-divider` | 2 | JobsView 2 |
| `el-container` / `el-aside` / `el-menu` / `el-main` | 各 1 | App.vue |
| `el-drawer` | 1 | FieldMappingWorkbench |
| `el-tabs` | 1 | FieldMappingWorkbench |
| `el-progress` | 1 | FieldMappingWorkbench |
| `el-autocomplete` | 1 | MappingRowList（`:150`） |
| `el-time-picker` | 1 | JobsView |
| `el-upload` | 1 | TemplatesView |
| `el-pagination` / `el-descriptions` / `el-radio-group` | 各 1 | HistoryView |

另外两个**不是标签但是 UI 面**的：`ElMessage` 48 处（`FieldMappingWorkbench` 9、`JobsView` 9、`useMappingDraft` 6、
`useWorkbenchState` 5、`DataSourcesView` 5、`HistoryView` 5、`TemplatesView` 5、`MappingRowList` 2、`SitesView` 2）、
`ElMessageBox` 15 处（`useMappingDraft` 4、`FieldMappingWorkbench` 3、其余各视图 2）。它们渲染在 body 上，走同一套 `--el-*`。

### 不好压样式的（判据：组件内部 DOM 不在你的模板里 / 弹层去了哪里）

| 组件 | 次数 | 现状证据 | 为什么难 |
|---|---|---|---|
| `el-drawer` | 1 | `FieldMappingWorkbench.vue:2-10`：`direction="rtl" size="100%" :with-header="false" class="mapping-workbench-drawer"`（`</el-drawer>` 在 `:167`） | **实测默认不 teleport**：drawer 复用 dialog 的 props（`es/components/drawer/src/drawer.mjs:5` `...dialogProps`），而 `appendToBody: Boolean` 默认 `false`（`es/components/dialog/src/dialog.mjs:14`），teleport 的 `disabled` 计算为 `appendTo !== "body" ? false : !appendToBody`（`drawer.vue_vue_type_script_setup_true_lang.mjs:50`）→ 内容留在组件树里，scoped 与 `:deep()` **够得着**。真正要另做的是：`:with-header="false"` 意味着头部完全自绘，抽屉本体只剩背景/阴影/滚动容器是 EP 的 |
| `el-tabs` + `el-tab-pane` | 1 + 3 | `FieldMappingWorkbench.vue:144-164`（JSONata / 规则 JSON / 试算），已有 1 条深度规则 `:878` | 下划线是 `border-bottom` 伪元素 + `--el-color-primary` 驱动的，字号/间距在 header 内部 |
| `el-table` + `el-table-column` | 10 + 60 | 5 个视图页面（编辑器里一个都没有） | 表头背景/边框/行 hover 全是组件内部 DOM 与 `--el-table-*` 变量 |
| `el-dialog` | 10 | 6 个文件（编辑器侧只有粘贴样例那个框） | 同样默认不 teleport（`appendToBody` 默认 false）；圆角/标题字号走 `--el-dialog-*`，但内容字号是字面 `14px`（§5） |
| `el-select` + `el-option` / `el-autocomplete` / `el-time-picker` | 16+31 / 1 / 1 | `JobsView` 10 个 select、`MappingRowList` 2 个 + 1 个 autocomplete | **这几个下拉真 teleport 到 body**：`el-select` 转发 `useTooltipContentProps.teleported`（`es/components/select/src/select.mjs`），`el-autocomplete` 默认 `teleported: true`（`es/components/autocomplete/src/autocomplete.d.ts:103` / `autocomplete.mjs` 的 `withDefaults`） |
| `el-menu`（侧边栏） | 1 + 5 | `App.vue` | 菜单色由 `:root` 第 4 个块的 `--el-menu-*`（15 个变量）决定 |

**一条本次实测出来的事实**：全仓 grep `popper-class|append-to-body|teleported` **0 命中**——
现在**没有任何弹层/抽屉的类名钩子**。要单独给某个下拉做局部样式，只能给组件加 `popper-class`（下拉）或写全局样式。
**判断**：真正难的是**下拉弹层**（teleport 到 body，scoped 够不着），而 `el-drawer`/`el-dialog`（默认不 teleport）比预想的**好压**——
这一点与「drawer/dialog 最难」的常见直觉相反，是本轮实测纠正过来的。

### 解读

编辑器只用到 **61 处 `el-*`**，其中直觉上最难压的 `el-drawer`/`el-tabs` 各只有 1 个实例——
**编辑器的「难压点」其实很小**；真正铺开在五个业务页面上的是 `el-table`(10) + `el-table-column`(60) + `el-form-item`(45) + `el-dialog`(视图侧 9) = **124 处**。
换言之：**令牌桥服务于「全站」，深度自绘服务于「编辑器」**，两件事不该混在一期。

---

## 5. Element Plus 的可覆盖性

数据源：`frontend/node_modules/element-plus/dist/index.css`——**单行、无结尾换行、357,817 字节**（element-plus 2.14.4）。

### 数字

| 指标 | 数量 | 数法 |
|---|---|---|
| `--el-*` 令牌出现次数 | **3457** | `--el-[a-z0-9-]+` 全局匹配 |
| `--el-*` 去重名字 | **567** | |
| 其中「被定义过」的（`--el-x:`） | **557** | 正则 `--el-[a-z0-9-]+\s*:` |
| 只被 `var()` 引用、全文件从未定义 | **10** | `--el-aside-width --el-timepicker-inner-border-color --el-timepicker-active-color --el-dropdown-menu --el-input-focus-color --el-menu-level --el-switch-border-color --el-table-scrollbar-size --el-hover-color --el-splitter-bar-offset` |
| `var(--el-…)` 引用处 | **2468** | |
| `:root` 块 | **4** | 括号配平切开 |
| `:root` 里定义的不同变量 | **137** | 118 + 2 + 2 + 15：主块 118 个；`--el-popup-modal-*` 2 个；`--el-loading-spinner-size` 系列 2 个；`--el-menu-*` 15 个 |
| 字面 hex 颜色（EP 自己的） | **312 处 / 96 种** | |
| 字面 `px` 声明 | **1406 处** | |

### 逐项判断（只覆盖 `:root` 够不够）

| 目标 | 结论 | 依据 |
|---|---|---|
| **主色** | ✅ 能，但**必须连浅档一起覆盖** | `:root` 有 `--el-color-primary:#409eff` 与 `--el-color-primary-light-3:#79bbff` 等——**浅档是字面值**，只改 `--el-color-primary` 会留下蓝色描边/浅底（`--el-button-hover-bg-color:var(--el-color-primary-light-9)`、`.el-tabs__item.is-active{color:var(--el-color-primary)}`） |
| **圆角** | ✅ 能（主档） | `--el-border-radius-base:4px`、`--el-border-radius-small:2px`、`--el-border-radius-round:20px`、`--el-border-radius-circle` 都在 `:root`，且被 `var()` 大量引用（按钮组、`--el-input-border-radius:var(--el-border-radius-base)`、`.el-autocomplete-suggestion`、`--el-alert-border-radius-base`…） |
| **边框色** | ✅ 能 | `--el-border-color` + `light / lighter / extra-light / dark / darker / hover` 全在 `:root` |
| **文字色** | ✅ 能 | `--el-text-color-primary / regular / secondary / placeholder / disabled` 全在 `:root` |
| **字号** | ⚠️ **只能改一半** | `var(--el-font-size-*)` 共引用 **68 处**（直接写在 `font-size:` 上的 62 处，其中 `--el-font-size-base` 独占 51；另有 6 处出现在别的自定义属性里），**但同文件里还有 170 处字面 `font-size:Npx`**（`12px`×69、`14px`×61、`16px`×16、`20px`×6、`24px`×5、`18px`×4…，`12px+14px` 占 130/170），其中相当一部分是组件级默认，例如 `.el-tag{--el-tag-font-size:12px}`、`.el-alert{--el-alert-title-font-size:14px;--el-alert-description-font-size:14px}`、`.el-dialog{--el-dialog-content-font-size:14px}` → 覆盖 `:root` 拿不到它们 |
| **字体** | ❌ **覆盖 `--el-font-family` 零效果** | 该名字在文件里只出现 **1 次**（就是 `:root` 的定义），`var(--el-font-family)` **0 次**；文件里也没有任何根级 `html{`（`IndexOf('html{')` = 0）或 `body{`（锚定正则只命中 `.el-card__body{`、`.el-dialog__body{`、`.el-drawer__body{` 这类后代选择器）；`frontend/index.html`（13 行）没有任何 `<style>`；`frontend/src` 里 11 处 `font-family` 全是编辑器/历史页的等宽小片段（`Consolas, monospace` 等）——**换字体必须自己写一条 `body{font-family:…}`** |

> 与既有实验记录的**一处不一致**（写下来备查）：`.tmp-attach-fm/experiment/README.md:54-55` 说
> 「Element Plus 的 reset 给 `body` 写了显式 `font-family`，覆盖 `--el-font-family` 不生效」。
> 本次实测 2.14.4 的 `dist/index.css` 里**没有**根级 `html/body` 规则；那份记录里「不补一条就回落成 Times New Roman」的现象，
> 更可能的原因是**全仓库本来就没有任何一条全局 `font-family`**。结论一致（要自己补一条），但归因不同。

### 我会覆盖的变量清单（建议；依据 §5 的 `:root` 实测 + `.tmp-attach-fm/applied/styles/tokens.css` 这份已有的可用草稿）

```
/* 1. 主色（含全部浅/深档，浅档必须显式覆盖） */
--el-color-primary, --el-color-primary-light-3, -light-5, -light-7, -light-8, -light-9, --el-color-primary-dark-2
/* 2. 状态色（原型是「单一信号色 + 状态只做信号」，四组一起收） */
--el-color-success/-light-3/-light-5/-light-7/-light-8/-light-9, 同 danger / warning / info
/* 3. 圆角 */
--el-border-radius-base, --el-border-radius-small, --el-border-radius-round, --el-border-radius-circle
/* 4. 字号（能改到 68 处 var(--el-font-size-*) 引用里的那些；170 处字面 px 改不到） */
--el-font-size-base, --el-font-size-small, --el-font-size-extra-small, --el-font-size-medium, --el-font-size-large, --el-font-size-extra-large
/* 5. 字体（只改变量没用，另需 body 一条，见上） */
--el-font-family  +  body{font-family:var(--font-body)} + body{font-size:var(--fs-body)} + body{background:var(--bg)} + body{color:var(--fg)}
/* 6. 文字色 */
--el-text-color-primary, --el-text-color-regular, --el-text-color-secondary, --el-text-color-placeholder, --el-text-color-disabled
/* 7. 边框色 */
--el-border-color, --el-border-color-light, --el-border-color-lighter, --el-border-color-extra-light, --el-border-color-dark, --el-border-color-darker, --el-border-color-hover
/* 8. 底色 / 填充 / 遮罩（原型只有 --bg / --surface 两级） */
--el-bg-color, --el-bg-color-page, --el-bg-color-overlay, --el-fill-color, --el-fill-color-light, --el-fill-color-lighter, --el-fill-color-extra-light, --el-fill-color-blank, --el-mask-color
/* 9. 侧边栏菜单（App.vue 的 el-menu）：:root 第 4 块那 15 个，至少这几个要跟 */
--el-menu-active-color, --el-menu-text-color, --el-menu-hover-text-color, --el-menu-bg-color, --el-menu-hover-bg-color, --el-menu-border-color
```
**这些覆盖不到、需要另写规则的地方**（都是实测出来的字面值）：`.el-tag{--el-tag-font-size:12px;--el-tag-border-radius:4px}`、
`.el-alert{--el-alert-title-font-size:14px}`、`.el-dialog{--el-dialog-content-font-size:14px}`、
`.el-button{--el-button-divide-border-color:#ffffff80}`。

### 解读

**令牌桥能把「库的外观」一次改完（主色、圆角、边框、文字、底色、菜单），但改不完「库写死的字号」，也完全改不了「字体怎么被用」。**
真正的坑不在 EP 这一侧，而在**生产组件里的 158 处 hex 与 53 处字号都是字面值，scoped CSS 的字面值压过令牌**——
`.tmp-attach-fm/experiment/README.md` 的实测已经写明这一点（「换令牌真正买到的是 #409eff → 绿、14 → 13.5、圆角 3/4 → 8」，
而 `.trial-*` 那一块「B 只换令牌」与「C 令牌+换名」实测完全相同）。**一期只能买到「不刺眼」，买不到「像原型」。**

---

## 6. 冲突点（为 EP 默认外观写死的选择器）

| 类型 | 数量 | 位置 |
|---|---|---|
| `:deep(...)` 深度选择器 | **4 处 / 3 个文件** | `FieldMappingWorkbench.vue:878`、`MappingExpressionEditor.vue:97`、`MappingExpressionEditor.vue:107`、`MappingPasteSampleDialog.vue:61` |
| `::v-deep` / `:global(...)` | **0** | — |
| `!important` | **0 处（整个 `frontend/src`）** | — |
| scoped 里直接写 `.el-*` 的 CSS 规则 | **1 条** | `FieldMappingWorkbench.vue:878`（就是上面那条） |
| JS 里查 EP 内部 DOM 的选择器 | **2 处**（不是样式，但同源风险） | `FieldMappingWorkbench.vue:679` `document.querySelector('.el-message-box')`、`:806` `document.querySelectorAll<HTMLElement>('.el-autocomplete-suggestion')` |

四处原文：

```css
/* FieldMappingWorkbench.vue:878 */
.wb-artifacts :deep(.el-tabs__header) { margin-bottom: 4px; }

/* MappingExpressionEditor.vue:97 与 :107（同一个选择器两条规则） */
.wb-expression-input :deep(textarea) { … }
.wb-expression-input :deep(textarea) { height: auto; }

/* MappingPasteSampleDialog.vue:61 */
.wb-paste-text :deep(textarea) { font-family: Consolas, monospace; font-size: 12px; }
```

**假阳性提醒**：`HistoryView.vue:50 / 54 / 162 / 165` 的 `:deep="2"`（`:deep="3"`）是 `vue-json-pretty` 的 **prop**，不是深度选择器。
按字面 grep `:deep` 会数出 8 处，真正的样式深度选择器只有 **4 处**。

### 解读

**没有任何 `!important`，`:deep()` 只有 4 处，且都在编辑器这一棵树上**——这是本次评估里最省心的一条：
换外观时不需要「拆一堆强行覆盖」，只需要跟着改这 4 条 + 1 条 `.el-tabs__header`（它们本身就是编辑器自己的规则，改令牌后顺手换掉即可）。
真正要留意的是那 2 处 JS 选择器：它们是**行为对 EP 内部类名的依赖**，如果二期顺手动 DOM 结构（比如把 `el-tabs` 换掉），
`hasOpenAutocomplete` 那套键盘逻辑会静默失效（`MappingRowList` 的 `↑↓/回车` 归下拉还是归编辑器，判据就在这里）。

---

## 7. 现有设计相关内容（docs/ 里有没有「不做视觉对齐」）

### 逐条引用（原文 + 出处；行号均为 LF 口径）

1. **`CONTEXT.md`（157 行）**：全篇是领域术语表，与 UI 沾边的只有 `字段映射规则`（`:93`）、`映射`（`:97`）、`转换`（`:101`）、`片段转换`（`:105`）、`草稿`（`:89`）、`试算`（`:115`）这类**语义**定义——
   **没有一条谈样式/视觉**（关键词扫描 `视觉|外观|配色|皮肤|高保真|还原` 在 `docs/` 里也全部落空）。
2. **`docs/adr/0006-frontend-backend-split-single-image.md:12`**：
   > 「**代码分离**：新建独立前端工程 `frontend/`，使用 Vue 3 + TypeScript + Vite + Element Plus，组件化开发；后端 REST API 不变，前端只消费现有接口。」
   ——只定了技术栈，没有外观口径。
3. **`docs/features/config-ui/README.md`（40 行）** `:3`：
   > 「Vue 3 + TS + Vite + Element Plus 独立前端工程；工程分离、部署合一。」
   `:7` 列了五个页面与路由；`:9`「字段映射的配置整份在**全屏抽屉**里…」。全篇**没有**样式/视觉条目。
4. **`docs/research/mapping-framework.md:147`**：全仓最接近「外观决定」的一句——
   > 「### 4.5 前端 UI 方案（复用 Element Plus，无新依赖）」
   其下 `:149-151` 三条组件选型（`el-table` expand 嵌套两层、`el-tree` 双树、`el-dialog` 做 ENUM/CONCAT、`el-select`/`el-autocomplete` 补全）
   **是旧三栏编辑器的方案**：`/sample-fields` 端点已随 #118 删除（文档自己在 `:149` 加了括注），旧编辑器已在 #107 整体删除（`docs/features/field-mapping/README.md:13`）。
   → 它现在只能读作「复用 Element Plus、不引新依赖」这一条约束，**不能**读作对某个具体外观的承诺。
5. **`docs/flows/field-mapping/concat-part-mapping.md:158-159`**：`docs/` 里**唯一一条真正的样式机制决定**——
   > 「**面板样式单独一份文件。** 片段面板的 `.wb-part` / `.wb-part-kind` / `.wb-part-params`
   > 放在 `frontend/src/styles/workbench-panel.css:29-32`（窄屏在 `:57-58`），因为组件里 scoped 的样式到不了另一个组件内部。」
   （这条在代码里被复述了两次：`styles/workbench-panel.css:1-13` 与 `styles/workbench-issues.css:1-12` 的块注释，
   以及 `MappingRowList.vue:827` 的注释。）
6. **`docs/adr/0011-field-mapping-editor-rework.md`（132 行）**：把原型当**布局证据**用，不是当皮肤契约——
   > `:26-27`「参照原件（`frontend/prototype/reference-field-mapping-studio.html`）证明了一件事：换成「每个目标字段一行」的布局，**后端的数据格式可以一个字都不改**」
   > `:35-36`「**不做拖拽、不做命令面板。** 主要靠点击和键盘。」（这是交互范围，不是外观）
   该 ADR 全篇没有出现「配色 / 令牌 / 字体 / 圆角 / 视觉」。
7. **`docs/adr/0012`（149 行）、`docs/features/field-mapping/README.md`（157 行）、`docs/flows/field-mapping/*`**：讲的是校验口径、存储形状、样例与试算，
   样式相关只有第 5 条那处「面板样式单独一份文件」。

### 结论（回答「有没有说过不做视觉对齐」）

**没有。`docs/` 下不存在任何「不做视觉对齐」「保持 Element Plus 默认外观」「界面样式不在本轮范围」之类的明文。**
现有书面约束只有两条：**① 复用 Element Plus、不引新依赖**（`mapping-framework.md:147`）；**② 跨组件共用的样式基元放独立样式表**（`concat-part-mapping.md:158`）。

要说「不换肤」这四个字，只能找到仓库里**未跟踪**的两处：

- `.tmp-attach-fm/field-mapping-canvas-brief.md:118`（第 10 节）：
  > 「**保留**：已锁定的令牌与字体（深浅两级中性 + 单一信号色，等宽承载路径与样例值）…**未换肤。**」
- `.tmp-attach-fm/DESIGN-HANDOFF.md:10`：
  > 「Treat this handoff as a visual contract: if implementation choices conflict, match the exported pixels and behavior first, then refactor internals.」
  `:88`：
  > 「Reject implementation shortcuts that flatten the design into generic cards, generic gradients, placeholder stats, or **framework-default typography**.」

**并且这两份都不在版本控制里**：`git status --porcelain` 显示 `?? .tmp-attach-fm/` 与 `?? frontend/prototype/`；
`git log -- frontend/prototype` 无输出。也就是说**三轮高保真原型（`reference-field-mapping-studio.html` 1891 行、`field-mapping-ledger.html` 1823 行）
加上 `.tmp-attach-fm/` 里的 `field-mapping-canvas.html` / `field-mapping-workbench.html` / `field-mapping-studio.html` 与设计说明，目前只活在未跟踪的工作区文件里。**

### 解读

视觉对齐这件事在**版本化的文档体系里既没有决定、也没有否决**——这既是机会（没有历史包袱要推翻）
也是风险（没有验收口径，做完了没法说「对上了」；而且唯一的参照物（原型 HTML）随时可能被一次 `git clean` 抹掉）。
`docs/research/` 现在已有三份文档，把这份评估落在这里与既有的 research 惯例一致。

---

## 8. 改造分期建议（**以下是我的判断**，每条都标注它依赖上面哪些数字）

> 事实与判断的分界：本节所有「文件数/行数/处数」都是前七节数出来的；所有「应该怎么分期」「风险高不高」都是我的判断。

### 第 0 期：先把决定写下来（0 行代码）

- 动作：写一条 ADR-0013（或等价记录）＋ 在 `docs/features/config-ui/README.md` 补一节视觉口径；
  把 `frontend/prototype/` 与 `.tmp-attach-fm/` 里那几支设计稿纳入版本控制（**事实**：现在都未被跟踪）。
- 为什么先做：**事实**是 `docs/` 里没有这条决定（§7），而唯一的参照物未被 git 跟踪。
  **判断**：不先固化，后面每一期都还要重新确认一次范围与验收标准。
- 风险：**无**。

### 第 1 期：令牌桥（改 2–3 个文件）

| 文件 | 变更 | 规模 |
|---|---|---|
| `frontend/src/styles/tokens.css` | **新增**（草稿现成：`.tmp-attach-fm/applied/styles/tokens.css`，92 行） | ~90 行 |
| `frontend/src/main.ts` | 加 1 行 `import './styles/tokens.css'`（草稿：`.tmp-attach-fm/applied/main.ts`） | 7 → 8 行 |
| （同文件里的全局 `body` 规则） | 字体/字号/底色/文字色——**必须写**，因为 `--el-font-family` 零引用（§5） | 4 行 |

- 覆盖清单见 §5。**依据（事实）**：`:root` 里 137 个变量、`--el-color-primary-light-3` 等浅档是字面值、`--el-font-family` 零引用、170 处字面字号。
- 收益（**事实，来自既有实测** `.tmp-attach-fm/experiment/README.md:21-33`）：页签文字色 `rgb(64,158,255)` → `oklch(48% .15 150)`、字号 14 → 13.5px、
  按钮圆角 3px → 7px、等宽字体 Consolas → JetBrains Mono；**一期不动任何组件**。
- 风险：**低**。两个**事实性**坑：① 浅档漏一个就是蓝边；② `main.ts` 是全量注册（§4），这一期会**同时改掉五个业务页面**（views + App 占 298/359 处 `el-*`）。
  **判断**：若只想改编辑器，就得把变量挂在抽屉根 `.mapping-workbench-drawer` 上而不是 `:root`——**事实**上可行（`el-drawer` 默认不 teleport，§4），
  **但**它够不到两个 teleport 到 body 的东西：`MappingRowList:150` 的 `el-autocomplete` 下拉与 `ElMessage`/`ElMessageBox`（§4），这两处会掉回默认蓝。

### 第 2 期：编辑器这棵树换名（改 13 个文件）

- 文件（**事实**）：11 个含 `<style>` 的 `.vue`（`FieldMappingWorkbench`、`MappingRowList`、`MappingSourcePanel`、`MappingTrialPanel`、
  `MappingExpressionEditor`、`MappingIssuesPanel`、`MappingPasteSampleDialog`、`App.vue`、`JobsView`、`HistoryView`、`DataSourcesView`）
  ＋ `styles/workbench-panel.css` ＋ `styles/workbench-issues.css`。
- 替换量（**事实**）：hex **158 处**（去重后的值 **43** 个，其中 15 个是中性灰/白）、`font-size` **53 处**（6 个值 → 归成 2 个令牌）、
  `border-radius` **14 处**（3 个值 → 归成 2 个令牌）、以及 4 处 `:deep()` + 1 条 `.el-tabs__header`（§6）。
- **不该做的事**（**判断**）：不要改那 86 个 `wb-*` 类名——`tools/ui/` 下 11 个验收脚本（3746 行）与 `docs/flows/field-mapping/*` 都把类名当锚点，
  改名只会制造无收益的连锁修改。
- 可复用的先例（**事实**）：`.tmp-attach-fm/replace-hex.cjs`（86 行）与 `replace-type.cjs`（43 行）就是上一轮做同样替换用的脚本。
- 风险：**中**。**事实**：编辑器是唯一有真机验收脚本的界面（`tools/ui/*.cjs` 11 个 / 3746 行，其中只有 `part-transform-112.cjs:171` 用了 `getComputedStyle`），
  所以断言基本不绑颜色；**判断**：即便如此也应把 `tools/ui` 全跑一遍，因为「颜色/字号变化 → 布局变化 → `↑↓/回车/退格` 的命中区域变化」
  是这类改造最常见的回归形态（`#106` 那套键盘逻辑对 DOM 结构很敏感，见 §6 的 2 处 JS 选择器）。

### 第 3 期：结构对齐（改 1–2 个组件 + 后端）

- 内容：原型试算页签的**结论条 / 计量条 / 证据表 / 结构化↔原始 JSON 切换 / 过期冻结横幅**（`field-mapping-canvas-brief.md` §12.3）。
- **事实**：`.tmp-attach-fm/experiment/README.md:61-63` 已写明这一步「**没有动 DOM 结构**…这部分要靠扩 `MappingTrialResult` 后端返回才能做（`metrics` / `entries` / `phase`）」。
- **判断**：这已经不是「视觉刷新」，而是信息架构改造。**如果本轮目标只是换外观，第 3 期应当明确排除在范围之外**（写进第 0 期的决定里）。

### 风险总表

| 期 | 动几个文件 | 样式面规模（数出来的） | 风险（判断） | 主要风险来源 |
|---|---|---|---|---|
| 0 | 0（文档） | 0 | 无 | — |
| 1 | 2–3 | ~95 行新 CSS（含全局 `body`） | 低 | 浅档漏覆盖（蓝边）；全量注册 → 影响面自动扩到全站；字体必须另写 `body` |
| 2 | 13 | 158 hex + 53 字号 + 14 圆角 + 5 处深度覆盖（384 行 CSS） | 中 | 键盘/窄屏在 `tools/ui` 里的真机验收；`#409eff` 手写在 9 条规则（12 处）里 |
| 3 | 1–2 + 后端 | 不计（新增结构） | 高（且超出「换外观」） | 需要后端先扩 `MappingTrialResult` |

---

## 附：本次没有做的事（避免把判断当事实）

- 没有起浏览器、没有真的注入令牌看效果——所有「能不能改到」的结论都来自读 `dist/index.css` 的静态证据；
  唯一一次真实渲染的实测记录是别人留下的 `.tmp-attach-fm/experiment/`（三张对照图 + `states.json`）。
- 没有在浏览器里验证 teleport 出去的下拉弹层（`el-select` / `el-autocomplete`）在 scoped 与 `:deep()` 下的真实可达性；
  本文关于 teleport 的结论只来自读 `es/components/*/src/*.mjs` 里的 props 默认值（`appendToBody` / `teleported`）。
- 没有评估 `frontend/dist/`（构建产物）与 Docker 打包链路；没有跑过 `tools/ui/*.cjs`。
- 本文的行数口径是 **LF 数**（见 §0 陷阱 2）。仓库里既有文档（例如 `docs/flows/field-mapping/README.md:159` 的 N4、
  `AGENTS.md` 的 token 预算一节）用的是另一种数法，两边的数字**不可直接相减**；要比就得先对齐口径。
