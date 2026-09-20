# 真机 UI 验收脚本（字段映射编辑器 + 消息历史页）

用真浏览器（Playwright）跑在**真的前端 + 真的后端**上的验收脚本：一个脚本一个功能点，
每个都对着当时那张票的验收项写。纯模块那侧的断言在 `frontend/src/mapping/checks.ts` 与
`frontend/src/history/checks.ts`（不碰 DOM、不发请求，`node --experimental-strip-types` 直接跑）；
这里管的是「界面上真点一遍会怎样」——DOM、发出去的请求、后端返回的问题。

## 首次准备

```powershell
cd tools/ui
npm i                                   # playwright 在本目录自带了（不用 NODE_PATH，不用全局装）
npx playwright install chromium         # 只有这台机器没有浏览器缓存时才要跑（约 150MB）
```

`package.json` 里 playwright 钉在 `~1.59.1`：机器上的浏览器缓存里正好是它要的 `chromium-1217`，
钉住就不用为了一个补丁版本再下一份浏览器。换新机器时上面第二句补上就行。

## 跑之前

1. **前端 dev server** 在 `http://127.0.0.1:5173`（`cd frontend; npm run dev`）。
2. **后端** 在 `http://127.0.0.1:8080`（IDE 跑 `EdgeGatewaySpikeApplication` 或命令行都行）。
3. **数据**：多数脚本用**任务 3（DBG-TS）**里那个 FIELD_MAP 动作（id 8，9 条已存规则，写目标是
   真表 `dbg_receipt`）。`array-mapping-101.cjs` 另要一张 `customer_db.dbg_product`
   （表结构见 `seed/t3-source-table.sql`）。脚本会自己读当前配置，不依赖某个固定内容。

## 跑法

```powershell
node part-transform-112.cjs        # 在哪个目录跑都行：截图落在 tools/ui/shots/，路径按脚本自身算
```

输出一行一项 `ok` / `NOT`，末尾是「全部通过」或「N 项没过」；没过时退出码非 0。
截图在 `tools/ui/shots/`（没进 git）。

## 脚本清单

| 脚本 | 票 | 验什么 | 会写库吗 |
|---|---|---|---|
| `array-mapping-101.cjs` | #101 | 在新版编辑器里配「制品 → BOM」层级映射（数组行、子行相对路径、「外层 · 」引用），保存后拿这份规则跑穿真链路核对目标端收到什么 | **会**：新建一条链（含任务与动作）留下 |
| `manual-rows-102.cjs` | #102 | 手工新增行、手输目标字段、清除/撤销；第二段在「探不出目标结构」的 HTTP 目标上全手输配完 | **会**：第一段改任务 3 后还原，第二段新建任务留下 |
| `draft-validation-103.cjs` | #103 | 「校验配置」只读接口 + 界面接线：没问题时的样子、重复目标字段报错落在行上、保存被拦下 | 不会（全程不保存） |
| `fast-config-105.cjs` | #105 | 来源搜索/过滤、已用计数与高亮、目标字段搜索跳行、一键展开收起、「自动匹配同名」只填空行且不发请求 | 不会 |
| `keyboard-narrow-screen-106.cjs` | #106 | 八个宽度下不溢出/不重叠/依次竖排、键盘走行换转换选来源清除、手写 JSONata 切过去存下切回来 | **会**：保存后还原任务 3 的动作配置（中途出错也还原） |
| `remove-old-editor-107.cjs` | #107 | 动作表上只剩一个「编辑映射」入口、弹窗里没有旧编辑器、老配置回填不丢字段、新建 FIELD_MAP 走同一入口 | **会**：动作 8 保存后还原 |
| `date-format-input-111.cjs` | #111 | 日期那一行的四个预设 + 手输框、清空回落默认格式、非法格式串报错并拦保存 | 不会（校验接口只读） |
| `part-transform-112.cjs` | #112 | 拼接片段六选一、日期/枚举参数就地展开、顶层「行号」置灰、草稿里的片段形状、片段报错指到那一个片段 | 不会（校验接口只读） |
| `paste-sample-115.cjs` | #115 | 来源面板顶部两个入口（探测 / 粘贴 JSON）、粘对象与粘数组各推出一份字段清单（数组注明「按第 1 条推的」）、换来源步骤只换归属不发探测、保存那份配置里没有粘进来的样例值、重开编辑器回到探测那条路 | 不会（PUT /actions 与 PUT samples 都被脚本拦下） |
| `target-fields-118.cjs` | #118 | 目标那一片两个入口（重新探测目标字段 / 粘贴 JSON）、粘一份带数组的目标数据推出的清单与连库探测同一形状（数组折成数组行 + 相对路径子行）、写明「必填推不出来」与「按第 1 条推的」、已配好的行没被冲掉、重新探测把清单与说法都换回去 | 不会（全程不保存） |
| `sample-stored-119.cjs` | #119 | 打开时存过的那份压过探测（来源顶上「粘的（已存）」、目标那一片不连库探测）、保存那一刻那两格的形状与先后（粘的带原文、探测的只报归属）、重开之后那份清单还在 | **会**：最后一段真写 `mapping_sample` 一格（要后端是新起的；旧后端时那一段 SKIP，其余照跑） |
| `message-history-129.cjs` | #129 | 消息历史页的双栏骨架与筛选：页内不滚动而两栏各自滚、任务 / 状态即时生效而业务键靠查询、重置回到初始、改筛选回第 1 页、翻页与改每页条数、加载中占位 / 空 / 筛没了 / 接口出错并重试、选中一条运行右栏是它的头与错误块 | 不会 |
| `message-history-130.cjs` | #130 | 消息历史页的计数条与运行摘要：页头四项（运行 / 消息 / 消息步 / 失败步）与聚合接口逐个对上、聚合请求与列表同一组筛选且同一页、**翻页不减计数**、改任务 / 业务键筛选计数跟着变并与接口重算一致、运行行写 `N 消息 · M 步[ · 失败 K]`（**不出现「失败 0」**、失败那段是红的）、**行摘要与按 runId 取到的消息步一致**、命中的业务键只在该筛时显示 | 不会 |
| `message-history-136.cjs` | #136 | 消息历史页第二个模式（消息步检索）：切模式改打 `/history/steps` 且**默认只看 FAILED**（这句话在列表头、筛选条、状态下拉占位上读得到）、「全部状态」才真的全看、业务键命中消息步、点一行定位到对应消息与步骤（右栏路径 + 行选中态 + 右栏取那条运行）、分页与每页条数同样有效、每行写全（运行 / 步骤 / 状态 / 动作 / 业务键 / 时刻 / 尝试 / 任务 / 错误首行） | 不会 |

## 后端是旧进程时

脚本里有几段断言依赖后端**已经带上**对应改动（`date-format-input-111.cjs` 的非法格式串、
`part-transform-112.cjs` 的片段报错、`message-history-136.cjs` 里消息步列表带的 `jobId`）。
脚本会先直接打一次接口问清楚，后端还没那一版时打印一行
`SKIP` 并跳过那一段（顺带说明后端的证据在哪几个 Java 用例里），其余照跑。**重启后端之后再跑就是真验。**

后端那些用例（同样是这些断言，只是不走浏览器）：

- 草稿校验 fixture：`spike/src/test/java/com/example/edgegateway/service/MappingDraftValidationServiceTest.java`
- 试算 fixture（整份跑通 / 逐行归因 / 手写表达式 / 不改任何东西）：`spike/src/test/java/com/example/edgegateway/service/MappingTrialServiceTest.java`
- 求值上限（超时与递归深度、不误伤大数组）：`spike/src/test/java/com/example/edgegateway/mapping/JsonataFieldMapperLimitsTest.java`
- 规则 → 编译 → 执行往返：`spike/src/test/java/com/example/edgegateway/mapping/JsonataRuleCompilerTest.java`
- 保存那道门：`spike/src/test/java/com/example/edgegateway/service/ActionServiceTest.java`
- 编辑器端点契约：`spike/src/test/java/com/example/edgegateway/web/FieldMappingEditorControllerTest.java`
- 消息历史（含 #136 消息步列表补 `jobId` 的投影查询、#130 计数与运行摘要的聚合查询）：`spike/src/test/java/com/example/edgegateway/service/MessageHistoryServiceTest.java`、
  `.../mapper/MessageHistoryMapperTest.java`、`.../web/HistoryControllerTest.java`
