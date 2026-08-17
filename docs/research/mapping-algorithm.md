# 字段映射匹配算法与转换推断调研

调研结论：为边缘网关的"字段映射智能化"（wayfinder 票「匹配算法与转换推断调研」）确定可落地的匹配算法与转换推断方案。

## 1. 三层匹配算法

**第 1 层：归一化与精确匹配**

- 归一化流程：小写 → 去下划线/空格/连字符 → camelCase 拆词（`FBillNo` → `f bill no`）→ token 列表。
- 归一化后相等（或 token 集合相等）直接得分 1.0。
- 内置别名词典处理常见缩写：`qty/quantity`、`no/number`、`dt/date`、`amt/amount`、`vendor/supplier`。中文先走同义词典，拼音匹配暂不投入。

**第 2 层：字符串相似度**

- 首选 **Jaro-Winkler**（Apache Commons Text 的 `JaroWinklerSimilarity`），对短字段名友好、有前缀加成，默认阈值 **≥ 0.85** 才建议。
- 备选 **Levenshtein / Damerau-Levenshtein**（`LevenshteinDistance`）做 token 级兜底（如"插一两个字符"的近名）。
- 依据：[Apache Commons Text similarity 包](https://svn.apache.org/repos/infra/websites/production/commons/content/proper/commons-text/javadocs/api-release/org/apache/commons/text/similarity/package-summary.html) 提供 JaroWinkler / Levenshtein / Jaccard / Cosine 等实现。

**第 3 层：历史映射复用（资产沉淀）**

- 按 `(源字段路径, 目标字段名)` 精确对查历史映射表，命中即最高优先级（得分 1.0 + 标记"历史复用"）。
- 作用域倾向**站点级**（每客户一套）；表结构与跨站点共享留待地图 fog 后续决策。

**候选冲突**：同一目标字段出现多个候选时按得分排序，UI 展示 top 3 供选择；无候选留空，不强猜。

## 2. 样例值转换推断

参考 Apache NiFi 的 Schema Inference 做法（取前 N 行推断类型、混合类型回退 STRING、无数据回退 STRING）：

- 取前 **20** 行样例（来自探针契约的 `samples`）。
- 全数字 → `NUMBER`；全布尔 → `BOOLEAN`；全部能解析为某一种日期格式 → `DATE`（记录检测到的 pattern）；字符串去重后 distinct 数量 ≤ 20 → `ENUM`（收集枚举值集合）；混合/空 → `STRING`。
- 日期格式检测：候选 pattern 列表（`yyyy-MM-dd`、`yyyy-MM-dd HH:mm:ss`、`yyyy/MM/dd`、ISO-8601 等）逐个尝试，全部样例匹配才定（等价于 commons-lang `DateUtils.parseDate` 的候选尝试思路；也可用 [DateTimeFormats（按样例解析）](https://hn.svelte.dev/item/43949061) 这类库）。
- 字段所有样例值相同 → 可建议 `CONSTANT`。
- 依据：[NiFi Schema Inference](https://deepwiki.com/apache/nifi/4.4-schema-inference#1)、[NiFi 取前 10 行推断类型](https://issues.apache.org/jira/browse/NIFI-12491)（我们取 20 行，实现可配置）。

## 3. 落地建议

- 实现 `MappingSuggester` 接口（对应地图票「MappingSuggester 接口与 LangChain4j 预留」），输入 = 源 fields（探针契约输出）+ 目标 fields + 历史映射，输出 = `List<MappingSuggestion{sourcePath, targetName, score, transform, confidence}>`。
- 实现顺序：归一化+精确 → Jaro-Winkler 相似度 → 转换推断 → 历史映射复用。
- 默认参数：Jaro-Winkler 阈值 0.85；枚举 distinct ≤ 20；推断用样例行数 20。
- 扩展点：LLM 实现（LangChain4j）作为同一接口的替换实现，规则引擎先行（对应地图 Destination 的"先确定性启发式，预留 LLM"）。

## 来源

- [Apache Commons Text similarity](https://svn.apache.org/repos/infra/websites/production/commons/content/proper/commons-text/javadocs/api-release/org/apache/commons/text/similarity/package-summary.html)
- [NiFi Schema Inference](https://deepwiki.com/apache/nifi/4.4-schema-inference#1)
- [NiFi: 用前 10 行推断类型](https://issues.apache.org/jira/browse/NIFI-12491)
- [Airbyte 数据映射综述（AI 辅助映射方向）](https://airbyte.com/data-engineering-resources/data-mapping)
- [DateTimeFormats: 按样例解析日期](https://hn.svelte.dev/item/43949061)
