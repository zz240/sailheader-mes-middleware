# 复杂 JSON→JSON 层级映射：开源框架与可视化方案调研

> 日期：2026-08-21
> 背景：边缘网关的字段映射（FIELD_MAP）当前只支持扁平点号路径 + 数组下标（`data.items[0].name`），对"两个层级一起"的嵌套映射（如 制品→BOM：源 header/items → 目标 header/bomLines）支持不足。本调研评估业界开源方案，为自研或引入提供依据。
> 来源均为官方文档 / GitHub / 规范等主来源，结论后标注来源。

## 1. 候选框架总览

| 方案 | 许可 | 技术栈 | 层级映射（嵌套/数组） | 可视化编辑器 | 可嵌入 Java/Spring | 离线边缘部署 |
|---|---|---|---|---|---|---|
| **AtlasMap** | Apache-2.0 | Java（后端）+ Angular（UI） | 强：terminal/parent/collection + For-each 集合映射 | 有（独立 Web UI，拖拽画布） | 运行时引擎可嵌入，UI 是独立应用 | 可（standalone jar / camel-atlasmap） |
| **JOLT** | Apache-2.0 | Java | 强：spec 描述结构变换（shift/默认/删除/基数），含数组与通配 | 无官方可视化（只有 spec 演示） | 是（jolt-core 纯库，camel-jolt 组件） | 可 |
| **JSONata** | MIT | JS 原版 + Java 移植（IBM/JSONata4Java、dashjoin/jsonata-java） | 极强：类 XPath 表达式 + map/filter/reduce，可输出任意 JSON 结构 | 官方无；第三方有 jsonata-viz、jsonata-visual-editor（npm） | 是（camel-jsonata 组件 / Java 移植） | 可 |
| **DataWeave** | 商业（MuleSoft） | Java（Mule 运行时） | 强 | 有（Anypoint Studio DataMapper，源/目标树 + 连线） | 受 Mule 运行时约束 | 不适用 |
| **Azure Logic Apps Data Mapper** | 商业（Azure） | VS Code 扩展 + XSLT | 强（树 + 连线 + 函数） | 有 | 否（云/VS Code，XSLT 运行时） | 不适用 |
| **Apache NiFi** | Apache-2.0 | Java | 中：JoltTransformJSON（结构）/ JSONata 表达式 | 流程画布（非映射级编辑器） | 否（NiFi 整体太重） | 不适用 |

## 2. 逐个评估（主来源）

### 2.1 AtlasMap —— 最接近"开箱即用的映射设计器"

- 定位：带交互式 Web UI 的数据映射解决方案，支持 JSON schema/实例、XML、Java 类；字段级映射 + 转换函数 + 条件；导出 `.adm` 映射定义，由 `camel-atlasmap` 组件在 Camel 路由中执行（[AtlasMap User Guide](https://docs.atlasmap.io/)）。
- 层级映射支持：字段分三类——**Terminal**（叶子）、**Parent**（复合对象）、**Collection**（数组）；支持 **For-each 迭代映射**（源集合字段 → 目标集合字段）、一对一/多对一/一对多（[AtlasMap User Guide §2.3/2.4](https://docs.atlasmap.io/)）。这正是"制品 header + items 数组 → BOM header + bomLines 数组"所需的能力。
- 可视化：独立 standalone jar（`java -jar atlasmap-standalone.jar`，浏览器 8585 打开画布），源/目标面板 + 拖拽连线 + 右侧映射细节面板（[AtlasMap User Guide §1](https://docs.atlasmap.io/)）。
- 许可与集成：Apache-2.0（Maven `io.atlasmap` 依赖许可页）；`camel-atlasmap` 组件自 Camel 3.7 起（[Apache Camel AtlasMap 组件](https://camel.apache.org/components/latest/atlasmap-component.html)）。
- 顾虑：UI 是独立 Angular 应用，**嵌入现有 Vue 表单较难**；映射定义（.adm）是 AtlasMap 自有格式，与我们的领域配置、映射历史、LLM 建议体系割裂；项目社区活跃度一般（GitHub 存在 archive/attic 仓库，如 [atlasmap-attic](https://github.com/atlasmap-attic/atlasmap-transformationApproach)）。

> **2026-08-21 补充（重要状态变更）**：AtlasMap 项目已**停止维护**，`camel-atlasmap` 组件在 **Camel 4.0 中被移除**（[CAMEL-19686](https://issues.apache.org/jira/browse/CAMEL-19686)：Atlasmap has been discontinued, it is bringing in some CVEs…remove it in 4.0）。本项目使用 Camel 4.10.2，**该组件在当前技术栈中不存在**；即使自行引入旧版本，也要背负已废弃组件 + 已知 CVE。此条直接否决 AtlasMap 路线（无论是全量集成还是 camel 组件通道）。

### 2.2 JOLT —— 结构变换的 Java 事实标准

- 定位：Java 写的 JSON→JSON 变换库，**变换规范本身是 JSON**；侧重结构变换，值处理需自定义 Java Transform（[bazaarvoice/jolt README](https://github.com/bazaarvoice/jolt)）。
- 层级映射：`shift` 变换支持数组、通配符 `*`、嵌套路径，可重组任意层级结构（官方 javadoc/单测）；NiFi 内置 `JoltTransformJSON` 处理器即基于它（[Apache NiFi JoltTransformJSON 文档](https://svn-us.apache.org/repos/asf/nifi/site/trunk/docs/nifi-docs/components/org.apache.nifi/nifi-standard-nar/1.16.3/org.apache.nifi.processors.standard.JoltTransformJSON/index.html)）。
- 可视化：无官方可视化编辑器（仅 spec 演示 [jolt-demo](https://github.com/bazaarvoice/jolt)）。
- 集成：纯 Java 库（jolt-core），Camel 有 `camel-jolt` 组件（Camel 2.16 起，[Apache Camel JOLT 组件](https://camel.apache.org/jolt.html)）。
- 顾虑：spec 是"写给开发者"的 DSL，与表单式配置哲学冲突；实施工程师上手成本高。

### 2.3 JSONata —— 表达能力最强，有 Java 移植

- 定位：轻量 JSON 查询/变换语言，类 XPath；map/filter/reduce + 用户自定义函数，可把结果格式化为任意 JSON 结构（[JSONata 官方文档](https://docs.jsonata.org/overview.html)）。
- 层级映射：一个表达式即可完成"header + items 数组 → header + bomLines 数组"的复杂变换；官方列出 Java 实现：IBM/JSONata4Java（原生实现）、dashjoin/jsonata-java（参考移植）（[JSONata 实现列表](https://docs.jsonata.org/overview.html)）。
- 可视化：官方无；第三方有 [jsonata-viz（Visionata，拖拽无码编辑器）](https://github.com/Dhi13man/jsonata-viz)、[jsonata-visual-editor（npm）](https://www.npmjs.com/package/jsonata-visual-editor)、[try.jsonata.org 在线练习](https://try.jsonata.org)。
- 集成：Camel 有 `camel-jsonata` 组件（Camel 3.5 起，仅 producer，[Apache Camel JSONata 组件](https://camel.apache.org/camel-spring-boot/next/starters/jsonata.html)）。
- 顾虑：表达式仍属 DSL，直接暴露给实施工程师与表单哲学冲突；但可作为"高级模式"的运行时通道。
- **维护状态（2026-08-21 复核）**：JSONata JS 原版活跃（npm 维护度 10/10，2.2.1 于 2026-05 发布）；Java 移植两条线都在维护——IBM JSONata4Java（README 已更新至 2025-09 工具链）与 dashjoin/jsonata-java（1:1 移植、宣称 100% 参考测试覆盖）；`camel-jsonata` 4.10.2 实际依赖 **dashjoin/jsonata-java 0.9.8**（非 IBM JSONata4Java），2025-07 仍在修数组输入问题（[CAMEL-22297](https://github.com/apache/camel/pull/18777)）。

### 2.4 DataWeave / Azure Data Mapper —— 交互设计参考（不可嵌入）

- DataWeave：MuleSoft 的变换语言 + Anypoint Studio 可视化 DataMapper（源/目标树 + 映射连线 + 函数），但属商业运行时，不可脱离 Mule（[MuleSoft DataWeave 文档](https://docs.mulesoft.com/dataweave/2.6/dataweave-cookbook-map-based-on-an-external-definition)）。
- Azure Logic Apps Data Mapper：VS Code 扩展的可视化映射画布，连线 源元素→函数→目标元素，底层 XSLT（[Microsoft Learn：Create Maps for Data Transformation](https://learn.microsoft.com/en-ie/azure/logic-apps/create-maps-data-transformation-visual-studio-code)）。**它们共同的交互范式（源/目标树 + 字段连线 + 转换函数）是最值得参考的 UX**。

### 2.5 后端执行框架对比（2026-08-21 补充）

> 本节回答"后端运行时有没有成熟转换框架可替代自研 FieldMapper"。结论：有，但不替换主通道，作为执行层的可选实现。

| 框架 | 许可 | 定位 | Camel 4.10 可用 | 强项 | 弱项 |
|---|---|---|---|---|---|
| **JOLT**（bazaarvoice/jolt） | Apache-2.0 | JSON→JSON 结构变换，spec 即 JSON | ✅ `camel-jolt`（4.10.x 有发布） | NiFi 内置同款、久经生产验证；嵌套/数组/重排/常量/`=concat` 齐全；spec 可机器生成 | spec 面向开发者；迭代行号等派生值不原生（需自定义 Java Transform）；无可视化 |
| **JSONata**（dashjoin/jsonata-java 等 Java 移植） | MIT | 表达式语言，表达能力天花板 | ✅ 直调 `com.dashjoin:jsonata`（camel-jsonata 4.10.2 同源引擎，S0 已验证） | 一个表达式可覆盖 BOM 全场景（拼接/行号/过滤/聚合） | 表达式 DSL，无法从表单反向生成 |

排除项：MapStruct/ModelMapper/Dozer/Orika 是 **Java bean 对象映射**，非 JSON→JSON 运行时转换；DataWeave 是商业 Mule 运行时；XSLT 面向 XML；AtlasMap 已停维护且组件在 Camel 4 移除（见 §2.1）；NiFi 太重（内部用的就是 JOLT）。

**决策（2026-08-21 更新）**：执行引擎定为 **JSONata**——前端仍传结构化规则，后端把规则编译为 JSONata 表达式并执行（**直调 dashjoin/jsonata-java**；camel-jsonata 组件因 4.10.2 仅支持静态 specName、不支持动态表达式而不用，详见 §4.6 S0 结论）；JOLT 降为备选（仅当 JSONata spike 不通过时再评估）。详见 §4。

## 3. 结论与推荐路线

本项目约束：实施工程师（非开发者）用表单配置、边缘离线单容器部署、映射历史/LLM 建议已成型、模板沉淀是产品核心。

**决策（2026-08-21 更新）：路线 A 先做（多级表格映射），执行引擎定为 JSONata——前端传结构化规则，后端编译为 JSONata 表达式并执行；路线 B 与 A 合并（手写表达式即高级通道）。JOLT 降为备选。**

### 路线 A（已决策）：递归层级规则树

- 语义：`FieldMappingRule` 升级为**递归树**——叶子=普通字段映射（现有逻辑不变），组节点=数组映射（带 `children`，"目标数组 ← 源数组"），组内子字段用**相对路径**；`$index`（迭代序号）、`$root`（跨层级引用）为特殊来源。
- 存储：仍是结构化 JSON（配置示例见 §4），映射历史/建议器/模板兼容，旧配置无 children 天然兼容、无需迁移；**JOLT spec 为后端编译产物，不落库**。
- 执行：前端传结构化规则 → 后端路由生成时编译为 **JSONata 表达式** → `camel-jsonata`（IBM JSONata4Java）执行；行号 `$i`、拼接 `&`、枚举 `$lookup` 原生支持，DATE 格式化注册一个自定义函数。
- 可视化：参照 AtlasMap / Azure Data Mapper / DataWeave 的"源树 ↔ 目标树 + 点选"交互，但做成**表单式多级表格**（外层=字段+数组组行，展开=组内子表），保持实施工程师无 DSL。
- 兼容：与现有 MappingSuggester（结构探测→建议→确认）、映射历史（站点+方向+源结构指纹）、模板导出天然兼容；无新依赖，可离线。
- 成本：JSONata spike 约 1 天（前置）、rules→表达式编译器 + 执行集成约 2~3 天、配置/校验/历史兼容约 1 天、前端多级表格 + 双树点选约 2~3 周；JSONata4Java（MIT，IBM 或 dashjoin 移植）纯 Java 库，可离线。

### 路线 B：与路线 A 合并（同一 JSONata 引擎）

- 后端引擎定为 JSONata 后，路线 B 不再是独立通道：表单生成的表达式覆盖常规场景，高级用户手写表达式即逃生通道（条件、聚合、跨层重排）。
- 存储以结构化规则为主（表单编辑/历史/建议/模板）；手写表达式场景允许 FIELD_MAP 直接存表达式（标注 `mode: EXPRESSION`）。
- 运行时统一走 `camel-jsonata` / IBM JSONata4Java（MIT，可嵌入，[JSONata4Java](https://github.com/IBM/JSONata4Java)）。

### 不建议

- **不引入 AtlasMap 全量**：UI 是独立 Angular 应用难嵌入 Vue 表单；.adm 格式与领域配置/历史/建议体系割裂；社区活跃度一般。
- **JOLT 降为备选**：结构变换强但维护休眠、值加工需自定义函数、spec 机器生成更绕；仅当 JSONata spike 不通过时再评估。
- **不做 DataWeave / Azure**：商业/云绑定，不可嵌入。

## 4. 路线 A 细化设计（多级表格映射，2026-08-21 定稿）

### 4.1 场景示例：制品 → BOM

目标结构：`bom`（层级 1：bomNo/productCode/productName）+ `bom.bomLines[]`（层级 2：lineNo/materialCode/materialName/qty/unit）。

### 4.2 多级表格（映射关系配置视图）

| 层级 | 目标路径 | 来源路径 | 迭代 | 转换 | 参数/说明 |
|---|---|---|---|---|---|
| 1 | bom.bomNo | product.productCode | – | CONCAT | 前缀 `BOM-` 拼接 |
| 1 | bom.productCode | product.productCode | – | STRING | 直接映射 |
| 1 | bom.productName | product.productName | – | STRING | 直接映射 |
| 1 | bom.bomLines | product.bomItems | FOR_EACH | 组 | 源数组每个元素 → 目标一行 |
| 2 | lineNo | `$index` | – | INDEX | 从 1 开始（startAt=1） |
| 2 | materialCode | materialCode | – | STRING | 相对组内路径 |
| 2 | materialName | materialName | – | STRING | 相对组内路径 |
| 2 | qty | qty | – | INT | 数字转换 |
| 2 | unit | unit | – | ENUM | PCS→件、SET→套 |

要点：层级 2 的来源是相对路径（相对"当前数组元素"），层级 1 是绝对路径；`$index` 生成行号；`$root` 预留跨层级引用。

### 4.3 存储配置（JSON）

```json
{
  "rules": [
    { "sourceStep": 1, "source": "product.productCode", "target": "bom.bomNo",
      "transform": "CONCAT", "parts": ["BOM-", { "path": "product.productCode" }] },
    { "sourceStep": 1, "source": "product.productCode", "target": "bom.productCode", "transform": "STRING" },
    { "sourceStep": 1, "source": "product.productName", "target": "bom.productName", "transform": "STRING" },
    {
      "sourceStep": 1, "source": "product.bomItems", "target": "bom.bomLines",
      "iterate": "FOR_EACH",
      "children": [
        { "target": "lineNo", "source": "$index", "transform": "INDEX", "startAt": 1 },
        { "target": "materialCode", "source": "materialCode", "transform": "STRING" },
        { "target": "materialName", "source": "materialName", "transform": "STRING" },
        { "target": "qty", "source": "qty", "transform": "INT" },
        { "target": "unit", "source": "unit", "transform": "ENUM", "enumMap": { "PCS": "件", "SET": "套" } }
      ]
    }
  ]
}
```

> **存储与执行分离**：`rules` 是唯一存储契约（表单编辑、映射历史、建议器、模板都基于它）；JSONata 表达式由后端在路由生成时从 rules 编译，只存在于运行时，不落库——避免表达式反解析回表单的难题。

### 4.4 数据模型与执行语义

- 存储模型（前端表单侧）：`FieldMappingRule` 增加 `List<FieldMappingRule> children`（非空即组节点）；旧配置无 children = 单层树，兼容。`$index`（迭代序号，配 `startAt`）、`$root`（跨层级引用）为特殊来源；新增 `TransformType`：`INDEX`（迭代序号）、`CONCAT`（片段拼接：常量片段 + 来源字段片段）。
- 执行（后端）：前端提交结构化 rules → 后端编译器把规则树转成 **JSONata 表达式**（对象构造表达结构、`$map` 表达数组迭代、`$i` 行号、`&` 拼接、`$lookup` 枚举）→ FIELD_MAP 片段在自定义 Processor 里**直调 dashjoin jsonata** 执行（`evaluate` 返回 java.util.Map/List，可直接进上下文列表），结果进上下文列表。**自研 FieldMapper 不再扩展**。
- 自定义函数（少量）：`DATE` 格式化 JSONata 无内置 strftime，注册一个自定义函数（JSONata4Java 扩展函数机制，S0 验证）；其余行号/拼接/枚举均原生表达。
- 校验：组节点至少 1 个 children；`$index` 行不允许填 source；组内不允许再选 sourceStep（继承组节点的）。

### 4.5 前端 UI 方案（复用 Element Plus，无新依赖）

- 结构：来源样例/目标样例经现有 `/sample-fields` 生成双树；数组节点带 `[]` 徽标。（这一条写的是当时的设想；`/sample-fields` 那个端点已随 #118 删掉——那个「样例 → 字段清单」的能力现在落在编辑器那两个「粘贴 JSON」入口上，见 `docs/features/field-mapping/README.md`）
- 主交互：左侧来源树、右侧目标树，点来源字段 → 点目标字段 → 自动落一行到映射表；数组组行在目标树点选后展开为内层子表。
- 组件落点：`el-table` expand 展开行（外层=层级 1 + 数组组行，展开行内嵌 `el-table`=层级 2）、`el-tree` 双树、`el-dialog` 做 ENUM 键值编辑与 CONCAT 片段编辑、`el-select`/`el-autocomplete` 做来源路径补全；推荐面板（已重做）沿用。

### 4.6 落地步骤

| 步骤 | 内容 | 工作量 |
|---|---|---|
| S0 | **JSONata spike（前置）**：BOM 场景手写表达式验证（数组/行号/拼接/枚举原生、DATE 自定义函数挂载、执行路径选型） | ✅ 已完成（2026-08-21，见下"§4.6 S0 结论"） |
| S1 | 后端：rules → JSONata 表达式编译器 + FIELD_MAP 片段改 JSONata 执行 + DATE 自定义函数注册 | ✅ 已完成（2026-08-21，commit e5d4bed，JsonataRuleCompilerTest 9 用例） |
| S2 | 后端：配置模型/校验/映射历史兼容（含 `mode: EXPRESSION` 手写场景） | ✅ 已完成（2026-08-21，commit ce50dad/6171891） |
| S3 | 前端：多级表格 + 双树点选 + ENUM/CONCAT 弹窗 | ✅ 已完成（2026-08-21，commit c1e24fd，Playwright 实测） |
| S4 | 建议器输出升级为"组 + 相对路径"；单值样例推荐 CONSTANT 问题已修复（commit 587172a） | ✅ 已完成（2026-08-21，commit 6541d89） |

#### §4.6 S0 结论（2026-08-21）

- **选型**：直调 `com.dashjoin:jsonata:0.9.10`（Camel 4.10.2 camel-jsonata 同源引擎的更新版），在自定义 Processor 里执行，**不引入 camel-jsonata 组件**。原因：4.10.2 的组件只支持 classpath/URL `specName`，不支持从 header/变量传入表达式（[CAMEL-22112](https://issues.apache.org/jira/browse/CAMEL-22112) 到 4.11.0 才修复）；本项目表达式按任务动态编译，组件路径需写临时文件且无法直接注入 DATE 自定义函数。引擎本身零依赖（160 kB、无 Jackson 耦合，输入输出即 `java.util.Map/List`），与当前上下文列表天然对接。
- **全部通过（JsonataS0SpikeTest，3 用例，用真实 ERP BOM 样例）**：完整 BOM 表达式（`bom.bomNo` CONCAT / `bomLines` `$map` 数组迭代 / `$i+1` 行号 / `$number` / `$lookup` 枚举）输出结构与预期一致；`$map(items, function($v,$i){...})` 的 `$i` 可靠。
- **发现 1（`.{}` 简写无 `$i`）**：`items.{...}` 简写上下文里 `$i` 求值为 null（dashjoin 0.9.10 实测），行号必须走 `$map` 的 `$i` 参数——**编译器统一用 `$map` 生成数组迭代**。
- **发现 2（DATE 自定义函数）**：`expression.registerFunction("dateFormat", new JFunction((input, args) -> ..., "<ss:s>"))` 可挂载，签名 `(string, string) → string`；实现需兼容 ISO 时间戳（`OffsetDateTime`/`Instant`/`LocalDateTime`）与**纯日期串**（`LocalDate`，如 `2026-08-21`）。
- **发现 3（框架表述修正）**：camel-jsonata 4.10.2 依赖的是 `com.dashjoin:jsonata:0.9.8`，不是 IBM JSONata4Java；§2.3/§2.5 已同步修正。
- **后续引擎能力备忘**：dashjoin 支持 `createFrame()` + `setRuntimeBounds(超时, 递归深度)`，可防手写表达式失控，S1/S2 接入时对 `mode:EXPRESSION` 场景强制启用。

#### §4.6 S1/S2 结论（2026-08-21）

- **S1（编译器 + 执行器）**：`JsonataRuleCompiler` 按目标路径建树生成 JSONata 对象构造器；顶层来源统一为 `$s_<step>` 变量（执行时按上下文绑定），组内子规则相对 `$g<d>`，行号 `$i<d> + startAt`，`$root` 是前缀（`$root.bomNo` → `$s_<step>.bomNo`）；DATE 经 `registerFunction("dateFormat", JFunction "<ss:s>")` 注入。`JsonataFieldMapper` 在路由构建时编译一次、跨消息复用（dashjoin 线程安全）。
- **实测发现 1（序列扁平化）**：`$map` 单元素结果会被 JSONata 序列扁平化退化为对象，编译器用 `[ $map(...) ]` 包装保证组输出恒为数组（本地 e2e 单行 BOM 验证）。
- **实测发现 2（输入类型）**：dashjoin 引擎只接受 JSON 类型，READ_SQL 行里的 `LocalDateTime/Timestamp` 等 JDBC 类型直接报错；新增 `JsonataInputs.normalize` 在求值前把日期/时间转 ISO-8601 字符串、byte[] 转 Base64（本地 e2e 旧任务复现并修复）。
- **S2（配置/历史/表达式模式）**：`FieldMappingConfig` 增加 `mode`（默认 RULES / EXPRESSION）+ `expression`；EXPRESSION 保存期校验表达式非空、RULES 模式带 expression 报错；表达式模式上下文 Map 作为求值根（`$`/`$$`），`$s_<step>` 变量照常可用。历史对递归规则兼容：顶层规则（含组节点）落库，组内 INDEX 子规则不入库（相对路径非可复用资产）；模板为纯 Map 存储天然兼容。无效配置（顶层 INDEX、组内 sourceStep、空 CONCAT parts）保存时 400。
- **e2e 验证（本地 Docker MySQL/RabbitMQ + HTTP mock）**：BOM 复杂层级（CONCAT/组/INDEX/INT/ENUM）输出与预期一致；旧扁平规则零回归；单元素数组输出仍为数组；mode:EXPRESSION 手写表达式（`$map`+`$count`）输出正确。

## 5. 主要来源

- AtlasMap User Guide：https://docs.atlasmap.io/
- Apache Camel AtlasMap 组件：https://camel.apache.org/components/latest/atlasmap-component.html
- AtlasMap Maven 许可（Apache-2.0）：https://deps.dev/maven/io.atlasmap%3Aatlas-java-model/2.5.2
- bazaarvoice/jolt README：https://github.com/bazaarvoice/jolt
- Apache Camel JOLT 组件：https://camel.apache.org/jolt.html
- Apache Camel JOLT 组件（4.x 目录，含 Spring Boot starter）：https://camel.apache.org/components/4.10.x/jolt-component.html
- Apache NiFi JoltTransformJSON：https://svn-us.apache.org/repos/asf/nifi/site/trunk/docs/nifi-docs/components/org.apache.nifi/nifi-standard-nar/1.16.3/org.apache.nifi.processors.standard.JoltTransformJSON/index.html
- JSONata 官方文档：https://docs.jsonata.org/overview.html
- Apache Camel JSONata 组件：https://camel.apache.org/camel-spring-boot/next/starters/jsonata.html
- Apache Camel JSONata 组件（4.10.x）：https://camel.apache.org/components/4.10.x/jsonata-component.html
- IBM/JSONata4Java：https://github.com/IBM/JSONata4Java
- CAMEL-19686（移除 camel-atlasmap）：https://issues.apache.org/jira/browse/CAMEL-19686
- MuleSoft DataWeave 文档：https://docs.mulesoft.com/dataweave/2.6/dataweave-cookbook-map-based-on-an-external-definition
- Microsoft Learn：Azure Logic Apps Data Mapper：https://learn.microsoft.com/en-ie/azure/logic-apps/create-maps-data-transformation-visual-studio-code
