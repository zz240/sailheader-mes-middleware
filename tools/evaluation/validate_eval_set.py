#!/usr/bin/env python3
"""评测集结构校验：替换/新增真实数据前跑一遍，保证脚本与 Java 测试能直接消费。

用法:
    python validate_eval_set.py [--eval-set eval-set.json]

校验项:
    - 顶层 cases 非空，id 唯一
    - source/target/expected 结构齐全，direction 合法
    - expected.target 必须存在于目标字段清单；可选字段给出警告（不计入自动填充率分母）
    - expected.source 必须存在于源字段清单（归一化后比较）
    - transform 取值合法；DATE/CONSTANT/ENUM 的参数与类型匹配
    - 必填目标字段没有 ground truth 时给出警告
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


VALID_TRANSFORMS = {"STRING", "INT", "NUMBER", "DATE", "ENUM", "CONSTANT", "BOOLEAN"}
VALID_DIRECTIONS = {"NORTH", "SOUTH"}


def normalize(name: str) -> str:
    """轻量归一化：小写 → 去分隔符/数字/数组下标 → 去非字母数字。"""
    text = str(name).lower()
    text = re.sub(r"\[0\]", "", text)
    text = re.sub(r"[^a-z0-9]+", "", text)
    return text


def errors_of(case: dict, seen_ids: set) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    case_id = case.get("id")
    if not case_id or not isinstance(case_id, str):
        errors.append(f"[{case_id}] 缺少字符串 id")
    elif case_id in seen_ids:
        errors.append(f"[{case_id}] id 重复")
    seen_ids.add(case_id)

    if case.get("direction") not in VALID_DIRECTIONS:
        errors.append(f"[{case_id}] direction 必须是 {sorted(VALID_DIRECTIONS)}")

    source = case.get("source") or {}
    target = case.get("target") or {}
    expected = case.get("expected") or []
    if not source.get("fields"):
        errors.append(f"[{case_id}] source.fields 不能为空")
    if not target.get("fields"):
        errors.append(f"[{case_id}] target.fields 不能为空")
    if not expected:
        errors.append(f"[{case_id}] expected 不能为空")

    source_names = {normalize(f.get("name", "")): f.get("name") for f in source.get("fields", [])}
    target_by_name = {f.get("name"): f for f in target.get("fields", [])}

    for e in expected:
        target_name = e.get("target")
        target_field = target_by_name.get(target_name)
        if target_field is None:
            errors.append(f"[{case_id}] expected.target '{target_name}' 不在目标字段清单中")
            continue
        if not target_field.get("required", False):
            warnings.append(f"[{case_id}] expected.target '{target_name}' 是可选字段，不计入自动填充率分母")
        if e.get("transform") not in VALID_TRANSFORMS:
            errors.append(f"[{case_id}] {target_name} transform '{e.get('transform')}' 不合法")
        src = e.get("source")
        if src and normalize(src) not in source_names:
            errors.append(f"[{case_id}] {target_name} expected.source '{src}' 不在源字段清单中")
        if e.get("transform") == "DATE" and "datePattern" not in e:
            errors.append(f"[{case_id}] {target_name} DATE 转换缺少 datePattern")
        if e.get("transform") == "CONSTANT" and "constant" not in e:
            errors.append(f"[{case_id}] {target_name} CONSTANT 转换缺少 constant")
        if e.get("transform") == "ENUM" and "enumMap" not in e:
            errors.append(f"[{case_id}] {target_name} ENUM 转换缺少 enumMap")

    expected_targets = {e.get("target") for e in expected}
    for f in target.get("fields", []):
        if f.get("required", False) and f.get("name") not in expected_targets:
            warnings.append(f"[{case_id}] 必填目标字段 '{f.get('name')}' 缺少 ground truth")
    return errors, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description="评测集结构校验")
    parser.add_argument("--eval-set", default="eval-set.json", type=Path)
    args = parser.parse_args()

    data = json.loads(args.eval_set.read_text(encoding="utf-8"))
    cases = data.get("cases", [])
    if not cases:
        print("错误：cases 为空")
        return 1

    seen_ids: set[str] = set()
    all_errors: list[str] = []
    all_warnings: list[str] = []
    for case in cases:
        case_errors, case_warnings = errors_of(case, seen_ids)
        all_errors.extend(case_errors)
        all_warnings.extend(case_warnings)

    if all_warnings:
        print("警告：")
        for warning in all_warnings:
            print("  - " + warning)
    if all_errors:
        print("校验未通过：")
        for error in all_errors:
            print("  - " + error)
        return 1
    print(f"校验通过：{len(cases)} 个场景，结构完整，可直接被脚本与 Java 测试消费"
          + (f"（{len(all_warnings)} 条警告）" if all_warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
