# 字段映射评测集与自动填充率脚本

「Wayfinder: 字段映射智能化」的评测资产，用于测量验收指标"字段自动填充率"。

## 文件

- `eval-set.json`：评测集（mock 占位，5 个场景、18 个必填目标字段），每个场景含源结构（字段/类型/样例原始行）、目标字段清单（必填标记）、人工确认的 ground truth
- `auto_fill_rate.py`：统计脚本，实现 #11/#13 决策的规则引擎（归一化精确 → Jaro-Winkler ≥ 0.85 → 历史映射复用 + 样例值转换推断），输出冷启动与带历史的自动填充率
- `validate_eval_set.py`：结构校验脚本，新增/替换真实评测对前跑一遍（校验字段清单、required、ground truth、转换参数一致性）

## 运行

```bash
python auto_fill_rate.py --eval-set eval-set.json [--threshold 0.85]
```

## 指标口径

- **自动填充率（配对）**：top1 建议的源路径与 ground truth 一致 / 必填目标数 —— 主口径
- **转换类型正确率**：配对正确且推断转换类型一致 / 必填目标数
- **参数完整率**：转换正确且 datePattern/constant 一致（enumMap 属人工语义，不计入）/ 必填目标数
- **覆盖度**：给出建议的目标数 / 必填目标数

## 当前基线（v1，mock 占位）

> 正式基线按「先 mock 这些」确认：以当前 mock 集作为 v1 基线，替换真实数据后重新跑脚本与
> Java 验收测试（`RuleMappingSuggesterEvalTest`）即可更新，无需改动代码。

| 口径 | 冷启动 | 带历史（同站点资产沉淀后） |
| --- | --- | --- |
| 自动填充率（配对） | 67% | 100% |
| 转换类型正确率 | 67% | 100% |
| 参数完整率 | 61% | 100% |
| 覆盖度 | 67% | 100% |

冷启动未命中的均为业务重命名字段（`order_no → sheet_no` 等），名称匹配无法发明，靠人工首次配置 + 映射历史沉淀。

带历史口径下参数完整率 100% 的前提是"历史带出确认参数"：复用时不重新按样例推断，而是沿用上次确认的 datePattern/enumMap/constant。

映射历史规则（Java 与 Python 实现一致）：

- 键 = (源结构指纹, 源字段, 目标字段)，按站点 + 方向过滤后传入；不同表/队列的同名字段互不串用
- 同键多条以最近确认（updatedAt）为准，平手取复用次数高者
- 多个 1.0 候选按 得分 → 复用次数 → 最近确认 排序

## 加入真实评测对

把真实源/目标字段对按 `eval-set.json` 的 `cases` 结构追加即可，脚本无需改动。替换 mock 时的注意点：

- `samples` 保留探针原始行（含重复值），枚举推断依赖重复占比（去重数 < 行数一半才判 ENUM）
- `required` 标记目标端必填字段，只有必填字段计入分母
- `expected.transform` 只比类型；`datePattern`/`constant` 单独比对，`enumMap` 不算参数正确性
- 改完先跑 `python validate_eval_set.py --eval-set eval-set.json` 校验结构，再跑统计与 Java 测试
