# 字段映射

字段映射 = 把上一步动作输出按规则转换成目标结构；规则由表单生成、后端编译为 **JSONata 表达式**执行。

## 当前设计（现状）

- **规则模型（递归树，T37）**：`FieldMappingRule(sourceStep, source, target, transform, constant, enumMap, datePattern, parts, startAt, children, iterate)`；`children` 非空即**组节点**（数组映射，源数组 → 目标数组，组内子字段用相对路径）；`$index` 行号（配 `startAt`）、`$root` 跨层级引用。
- **转换类型**：STRING / INT / DATE（`dateFormat` 自定义函数）/ ENUM（`$lookup`）/ CONSTANT / INDEX / CONCAT（`&` 拼接）。
- **执行引擎（T36）**：`JsonataRuleCompiler` 在路由构建时把 rules 编译为 JSONata 表达式；`JsonataFieldMapper`（dashjoin jsonata 0.9.10）跨消息复用求值；上下文各步骤绑定 `$s_<step>` 变量，根对象为上下文 Map；READ_SQL 行的 JDBC 类型先经 `JsonataInputs.normalize` 转 JSON 安全值。
- **模式**：`mode: STRUCTURED`（默认，rules）+ `mode: EXPRESSION`（手写 JSONata，逃生通道，保存期校验）。
  注：代码里实际是「不是 EXPRESSION 就当 RULES」（`FieldMappingConfig`），文档口径以代码为准。
- **建议器（ADR-0005 / T39）**：`MappingSuggester` 接口，默认规则实现（结构探测、同名归一化、相似度、历史复用、转换推断），站点级可覆盖为 LLM（Ollama，配置 `edge.mapping.suggester=llm`）；数组路径（`items[0].x`）生成**组建议**（MatchSource.ARRAY）；单值样例不再推荐 CONSTANT（DS-10.4）。
- **编辑器（T38/T39，旧）**：三栏（左来源树 / 中映射表 / 右目标树）+ 数组组行展开子表 + ENUM/CONCAT 弹窗 + 建议面板（表格/分页/已采用标记）；保存/回填递归 rules，旧扁平配置兼容。
- **编辑器（新版，ADR-0011 / #99 起）**：全屏抽屉，**每个目标字段一行**、一行一个转换；左边常驻来源字段清单（按默认来源步骤列出字段、类型、样例值），点一下填进当前行；界面上不出现 `$` 符号。顶部默认来源下拉，进度条只统计必填字段。动作表上临时并存「编辑映射（新版）」入口，旧编辑器在 #107 里删。
- **数组映射（新版，T3 / #101）**：数组类型的目标字段占一行，在这一行上选**来源数组**；下面展开子行（分组标题「数组元素内」，可展开/收起、可「+ 子字段」），子行的目标字段与来源都写**相对路径**。子行要取数组外层的表头字段时，从左边清单的「外层」分组点一项，界面显示成「外层 · 字段名」，规则里存成 `$root.<路径>`，编译器还原成 `$s_<step>.<路径>`。**「外层」不是「上一层」**：它始终指这份来源数据的顶层对象，直接引用父级数组元素在现有编译规则下做不到。
  三处来源写法落进规则是同一种东西的三种形状（行上有 `iterate='FOR_EACH'` 的是数组行）：

  | 界面 | 规则里的 `source` | 编译产物 |
  |---|---|---|
  | 数组行选「数组」分组里的字段 | 数组路径（`items`） | `$map($s_1.items, ...)`，外层套 `[ ]` |
  | 子行选「数组元素内」的字段 | 相对路径（`materialCode`） | `$g0.materialCode` |
  | 子行选「外层 · 」的字段 | `$root.<路径>` | `$s_1.<路径>` |
- **代码位置**：`mapping/`（JsonataRuleCompiler、JsonataFieldMapper、JsonataFunctions、JsonataInputs、FieldMappingConfig）、`mapping/suggest/`、`web/SuggestionController.java`、`frontend/src/components/MappingEditor.vue`、`frontend/src/components/FieldMappingWorkbench.vue`、`frontend/src/mapping/`（`source-picker.ts` = 来源清单与「点一下填行」）。

## 决策时间线

| 日期 | 事件 | 为什么 | 被谁修正 | 锚点 |
|---|---|---|---|---|
| 08-16 | 智能化方向：规则启发式优先，LLM 可替换 | 配置最耗时、需可解释可离线 | 引擎选型后续定 | ADR-0005 |
| 08-19 | addition 类型化（动作侧） | 与数据源同源：每种类型各自处理 | — | DS-2 |
| 08-21 | 引擎选型：**JSONata**（弃 JOLT/AtlasMap/自研） | JOLT 维护休眠且行号弱、AtlasMap 停维护、JSONata 活跃且 A+B 合一 | — | DS-10.2 / research/mapping-framework |
| 08-21 | 多级表格 + 递归组规则编辑器（三栏） | 制品→BOM 两级层级映射 | — | T38 / c1e24fd / f33bfe4 |
| 08-21 | 建议面板改表格+分页+已采用标记 | 候选多、无标记 | — | DS-10.1 |
| 08-21 | 保存静默失败修复 | ENUM 的 JSON.parse 在 try 外 | — | DS-10.3 |
| 08-21 | 建议器组输出（数组识别） | 目标数组生成组建议 | — | T39 / 6541d89 |
| 08-24 | 单值样例不再推荐 CONSTANT | 样例单值≠常量，语义错误 | — | DS-10.4 / 587172a |
| 09-14 | 新版编辑器：每个目标字段一行 + 校验交后端只读接口 | 来源只能手打、配错要等跑起来才知道、层级看不出来 | — | ADR-0011 / #99 |
| 09-14 | 数组映射在三处来源写法上分开：数组 / 相对路径 / 外层引用 | 三种东西填串了就是自相矛盾的规则，后端不报错 | — | #101 / source-picker.ts |

## 待办 / 已知限制

- 层级路径支持“数组迭代段 + 相对路径”，深层任意重排仍建议走 `mode:EXPRESSION`；
- 映射历史对组规则：组行落库、INDEX 子规则不入库（T37 约定）。
- 嵌套数组（数组元素里还有数组）不做，深层重排走手写 JSONata（#98 F1）。
- 来源清单只认「链上更早那个动作最近一次成功运行的输出」：**MQ 消费动作不记成功步**，所以 MQ 打头的任务在编辑器里没有来源样例（`FieldMappingEditorService.latestOutput` 按 step 查 `message_step`）。走 `READ_SQL` 打头的链不受影响。
- 源里数组必须是**真数组**：直接读 MySQL 的 JSON 列拿到的是字符串（JDBC 行为），`$map` 会把整串当一个元素——那是源那边的形状问题，不是字段映射的问题。

## 当前生效

- 执行引擎：JSONata（dashjoin），规则→表达式编译器（T36）
- 规则模型：递归 children + mode:EXPRESSION（T37）
- 编辑器：新版逐行编辑器（全屏抽屉，含数组映射的展开/收起与「外层 · 」引用）；旧三栏编辑器在 #107 里删
