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
- **手工新增行（T4 / #102）**：目标字段清单不全（HTTP / MQ 做目标时探不出结构，见 #98 H3）时也能把配置配完：「+ 字段映射」加一行空的字段映射、「+ 数组映射」加一行空的数组映射、「+ 行号」/「+ 子字段」在数组行下面加子行。目标字段**可以从清单里选，也可以直接手输一个清单里没有的路径**（补全清单按这一行的形状给：普通行给标量字段、数组行给数组字段、子行给数组元素内的相对路径；清单之外的路径标「手写」）。每一行能**清除**（清的是这一行「值怎么取」，目标字段与数组行的子行都留着），清除后行里出现「撤销」，把那一份配回来。
- **校验配置（T5 / #103）**：编辑器顶部一个「校验配置」按钮，把草稿 POST 给后端只读接口 `POST /api/field-mapping-editor/validate`。请求带：草稿（`{mode? / expression? / rules[]}`，就是保存会写出去的那份）、目标字段清单、来源样例、链上更早的动作序号、默认来源步骤、草稿所在动作的序号；响应带 `errors[]` / `warnings[]` / 编译产物文本（`compiled.jsonata` 与 `compiled.rulesJson`）。**它不写数据库，也不改 `FieldMappingRule` 的格式。**
  - **判断对错全在这个接口里，前端一条校验规则都不复制**（#98 D1）：前端只装请求（草稿 = `configFromRows`，没配好的行本来就不进规则）和把回来的问题按「目标字段 + 它所属的数组行」挂到行上（`frontend/src/mapping/draft.ts`）。
  - 后端复用三份现成实现，不重写一遍结构校验：`FieldMappingConfig.problems()`（`validate()` 抛的就是它的头一条）、`JsonataRuleCompiler.compile`（编译期才发现的重复目标 / 前缀冲突也由它报）、`ChainConsistencyValidator.checkRules`（链级校验与草稿校验共用同一条规则）。
  - 每条问题带 `target`（出错那条规则的目标字段，数组子行写相对路径）与 `parent`（所属数组行的目标字段，顶层为 null）——界面据此定位到行：行右侧「有错 / 有警告 / 没问题」三种状态、问题列表点一下跳到那一行（收起的数组行会先展开）、保存时先校验一次（有错就拦下并跳到第一条错误）、底部两个标签页（JSONata / 规则 JSON）。
  - **顶层规则漏写 `sourceStep` 在这里是一条提示，不是错误**：后端把「没写」当成任务输入（0），查不出这是个错（#98 D10）。
  - 它替代不了 `POST /api/jobs/{id}/validate`：那个按 job id 校验**已经存进数据库**的配置，而编辑器里的还没保存。
  - 同一目标字段出现在两行时（用户加了一行重复的），问题挂到**后加**的那一行上——和后端编译器的口径一致（它在后出现的那条规则上报「重复映射」）。
- **代码位置**：`mapping/`（JsonataRuleCompiler、JsonataFieldMapper、JsonataFunctions、JsonataInputs、FieldMappingConfig）、`mapping/suggest/`、`web/SuggestionController.java`、`web/FieldMappingEditorController.java`、`service/FieldMappingEditorService.java`（编辑器上下文）、`service/MappingDraftValidationService.java`（校验草稿）、`frontend/src/components/MappingEditor.vue`、`frontend/src/components/FieldMappingWorkbench.vue`、`frontend/src/mapping/`（`rows.ts` = 行模型与清除/撤销快照，`editor-model.ts` = 目标清单 ↔ 行、目标字段补全与「+ 字段映射 / + 数组映射」，`source-picker.ts` = 来源清单与「点一下填行」（含数组行的「+ 子字段 / + 行号」），`draft.ts` = 校验请求与问题定位到行）。

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
| 09-14 | 手工新增行 + 清除/撤销 | 目标字段清单不全（HTTP/MQ 做目标时探不出结构）时，参照原件那种「行全部由清单生成」的编辑器配不完 | — | #102 / editor-model.ts、rows.ts |
| 09-14 | 「校验配置」：草稿校验收进后端一个只读接口 | 前端不复制任何一条校验规则；错要能定位到行、保存前拦得住 | — | #103 / ADR-0011 / MappingDraftValidationService |

## 待办 / 已知限制

- 层级路径支持“数组迭代段 + 相对路径”，深层任意重排仍建议走 `mode:EXPRESSION`；
- 映射历史对组规则：组行落库、INDEX 子规则不入库（T37 约定）。
- 嵌套数组（数组元素里还有数组）不做，深层重排走手写 JSONata（#98 F1）。
- 来源清单只认「链上更早那个动作最近一次成功运行的输出」：**MQ 消费动作不记成功步**，所以 MQ 打头的任务在编辑器里没有来源样例（`FieldMappingEditorService.latestOutput` 按 step 查 `message_step`）。走 `READ_SQL` 打头的链不受影响。
- 源里数组必须是**真数组**：直接读 MySQL 的 JSON 列拿到的是字符串（JDBC 行为），`$map` 会把整串当一个元素——那是源那边的形状问题，不是字段映射的问题。
- **校验不是实时的**（#98 决定）：点「校验配置」才跑一次，改完行要再点一次；界面上给出的行状态是那一次的结果，不随编辑自动失效。
- **基于目标清单与来源样例的六条检查还没做**（#104）：必填的目标字段没有配置来源、来源字段在样例里不存在、枚举表没覆盖样例里出现过的值、文本值直接写进数字类型的字段、拼接里存在空片段——请求里已经带着清单和样例，接口形状不用再改。
- 校验只做结构判断，**不做值的试算**（#98：需要在受控边界里真的执行表达式，是另一件事）。

## 当前生效

- 执行引擎：JSONata（dashjoin），规则→表达式编译器（T36）
- 规则模型：递归 children + mode:EXPRESSION（T37）
- 编辑器：新版逐行编辑器（全屏抽屉，含数组映射的展开/收起与「外层 · 」引用、手工新增行与清除/撤销、点一下「校验配置」看问题与编译产物）；旧三栏编辑器在 #107 里删
