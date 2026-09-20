# 字段映射（转换流）→ 已按映射形态拆份

> **本文件自 2026-09-16 的第三轮走查起不再维护正文。** #117 把 2187 行的 `FieldMappingWorkbench.vue`
> 拆成「工作台 + 四块子组件 + 两处组合式函数」，旧正文里的文件与行号大面积失效；同一轮把字段映射
> 按**映射形态**拆成五份独立走查文件。正文看 [field-mapping/README.md](field-mapping/README.md)。

## 导航

| 形态 | 场景 | 产物文件 |
|---|---|---|
| 标量行 | 一行一个目标字段 → 行级转换（STRING/INT/DATE/ENUM/CONSTANT/INDEX）→ 编译求值 | [field-mapping/scalar-row-mapping.md](field-mapping/scalar-row-mapping.md) |
| 数组 / 层级 | 数组行 + 子行（相对路径）+ 外层引用（`$root`）→ `$map` 组规则 | [field-mapping/array-mapping.md](field-mapping/array-mapping.md) |
| 拼接 / 片段 | `CONCAT` 多段拼接 + 每段挂一个片段转换 → `&` 表达式 | [field-mapping/concat-part-mapping.md](field-mapping/concat-part-mapping.md) |
| 手写表达式 | `mode=EXPRESSION`：整段 JSONata，跳过规则树校验 | [field-mapping/expression-mode.md](field-mapping/expression-mode.md) |
| 建议器 | 结构探测 → 同名归一化 → 相似度 → 历史复用 → 转换推断（当前无 UI 入口） | [field-mapping/suggester.md](field-mapping/suggester.md) |

## 旧编号对照（ADR 与功能文档里的引用照这张表找）

`docs/adr/0011-field-mapping-editor-rework.md`、`docs/adr/0012-field-mapping-editor-round-two.md`
与 `docs/features/field-mapping/README.md` 用旧编号引用了本文件的建议条目，编号本身保持不变，只是换了落点：

| 旧编号 | 讲的是什么 | 现在在哪 |
|---|---|---|
| C6 | 旧 `mapping/FieldMapper.java` / `MappingRule.java` 残留已删 | [scalar-row-mapping.md](field-mapping/scalar-row-mapping.md) |
| P6 | 顶层规则漏写 `sourceStep` 缺省指向 `$s_0` | [scalar-row-mapping.md](field-mapping/scalar-row-mapping.md) |
| P7 | DATE 对无法解析的值静默原样透传 | [scalar-row-mapping.md](field-mapping/scalar-row-mapping.md) |
| P8 | INT 用 `$number()`，非数字输入实测抛异常导致任务 FAILED | [scalar-row-mapping.md](field-mapping/scalar-row-mapping.md) |
| P9 | 组内只能引用当前层 `$g<depth-1>` 或 `$root`，中间层引用不到 | [array-mapping.md](field-mapping/array-mapping.md) |
| 映射历史 INDEX 子规则不入库 | 组行落库、INDEX 子规则不入库（T37 约定） | [array-mapping.md](field-mapping/array-mapping.md) |
| 深层数组重排走 `mode:EXPRESSION` | 层级路径只支持「数组迭代段 + 相对路径」 | [expression-mode.md](field-mapping/expression-mode.md) |
| F1、F2/F6、F3、F4、F5、F7/F8、F9 | 建议器的缺陷与待评估项 | [suggester.md](field-mapping/suggester.md) |
| 单值样例不推 CONSTANT | DS-10.4 | [suggester.md](field-mapping/suggester.md) |

> 判据：走查只产出文档与建议清单，不实施业务建议（缺陷修复除外）；建议按 Matt 流程路由到对应技能，
> 见 [README.md](README.md) 文末。
