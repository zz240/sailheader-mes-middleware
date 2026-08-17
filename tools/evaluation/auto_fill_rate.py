#!/usr/bin/env python3
"""字段映射自动填充率统计脚本（wayfinder 评测集配套）。

实现 #11/#13 决策的确定性规则引擎（归一化精确 → Jaro-Winkler → 历史映射复用）
与样例值转换推断（INT/BOOLEAN/DATE/ENUM/CONSTANT/STRING），对评测集跑一遍，
输出每场景与总体的自动填充率（冷启动 vs 带历史）。

用法:
    python auto_fill_rate.py [--eval-set eval-set.json] [--threshold 0.85] [--topk 1]

指标口径:
    - 自动填充率（配对）: 系统给出 top1 建议且源路径与 ground truth 一致 / 必填目标数
    - 转换类型正确率: 配对正确 且 推断转换类型一致 / 必填目标数
    - 参数完整率: 转换正确 且 datePattern/constant 一致（enumMap 属人工语义，不计入） / 必填目标数
    - 覆盖度: 给出 top1 建议的目标数 / 必填目标数
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path


# ---- 1. 归一化与别名（对应 #11 第 1 层）----

ALIASES = {
    "qty": "quantity",
    "no": "number",
    "dt": "date",
    "amt": "amount",
    "vendor": "supplier",
}


def normalize(name: str) -> tuple[str, frozenset[str]]:
    """小写 → 去分隔符 → camelCase 拆词 → 别名归一 → (紧凑串, token 集合)。"""
    text = re.sub(r"[\[\]0-9_\-./\\ ]", "", str(name).lower())
    text = re.sub(r"([a-z])([A-Z])", r"\1 \2", name)
    text = text.lower()
    tokens = [
        ALIASES.get(t, t)
        for t in re.split(r"[^a-z0-9]+", text)
        if t
    ]
    compact = "".join(tokens)
    return compact, frozenset(tokens)


# ---- 2. Jaro-Winkler 相似度（对应 #11 第 2 层）----

def _jaro(s1: str, s2: str) -> float:
    if s1 == s2:
        return 1.0
    l1, l2 = len(s1), len(s2)
    if l1 == 0 or l2 == 0:
        return 0.0
    window = max(l1, l2) // 2 - 1
    if window < 0:
        window = 0
    s1_matches = [False] * l1
    s2_matches = [False] * l2
    matches = 0
    for i in range(l1):
        lo = max(0, i - window)
        hi = min(i + window + 1, l2)
        for j in range(lo, hi):
            if not s2_matches[j] and s1[i] == s2[j]:
                s1_matches[i] = s2_matches[j] = True
                matches += 1
                break
    if matches == 0:
        return 0.0
    transpositions = 0
    k = 0
    for i in range(l1):
        if not s1_matches[i]:
            continue
        while not s2_matches[k]:
            k += 1
        if s1[i] != s2[k]:
            transpositions += 1
        k += 1
    transpositions //= 2
    return (matches / l1 + matches / l2 + (matches - transpositions) / matches) / 3


def jaro_winkler(s1: str, s2: str, p: float = 0.1) -> float:
    jaro = _jaro(s1, s2)
    prefix = 0
    for a, b in zip(s1, s2):
        if a != b:
            break
        prefix += 1
        if prefix == 4:
            break
    return jaro + prefix * p * (1 - jaro)


# ---- 3. 样例值转换推断（对应 #11 转换推断）----

DATE_PATTERNS = [
    ("yyyy-MM-dd HH:mm:ss", r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}$"),
    ("yyyy-MM-dd", r"^\d{4}-\d{2}-\d{2}$"),
    ("yyyy/MM/dd", r"^\d{4}/\d{2}/\d{2}$"),
    ("ISO-8601", r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$"),
]


def infer_transform(samples: list) -> dict:
    """返回 {type, datePattern?, constant?}；规则见 #11（取前 20 行原始样例）。

    ENUM 的判定要求去重后取值数不超过总行数的一半：探针返回的是原始行，
    重复占比高才像封闭枚举（如状态列）；单号/编码列即使有少量重复也判 STRING。
    """
    values = [v for v in (samples or []) if v is not None]
    if not values:
        return {"type": "STRING"}
    if all(isinstance(v, bool) for v in values):
        return {"type": "BOOLEAN"}
    if all(isinstance(v, int) and not isinstance(v, bool) for v in values):
        return {"type": "INT"}
    if all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in values):
        return {"type": "NUMBER"}
    for pattern, regex in DATE_PATTERNS:
        if all(isinstance(v, str) and re.match(regex, v) for v in values):
            return {"type": "DATE", "datePattern": pattern}
    distinct = set(str(v) for v in values)
    if len(distinct) <= 20 and len(distinct) >= 2 and len(distinct) < len(values) * 0.5:
        return {"type": "ENUM"}
    if len(distinct) == 1:
        return {"type": "CONSTANT", "constant": next(iter(distinct))}
    return {"type": "STRING"}


# ---- 4. 建议器（对应 #13 的规则引擎）----

MATCH_SOURCE = {"HISTORY", "EXACT", "SIMILAR"}


@dataclass
class Suggestion:
    source: str
    target: str
    score: float
    match_source: str
    transform: dict


def suggest(
    source_key: str,
    source_fields: list[dict],
    target_fields: list[dict],
    history: dict,
    threshold: float,
) -> list[Suggestion]:
    """为每个必填目标字段给出 top1 建议。

    历史键 = (源结构指纹, 源字段, 目标字段)；同键多条取最近确认；
    历史命中时带出上次确认的转换参数；多个候选按 (得分, 复用次数, 最近确认) 排序。
    """
    norm_cache = {}

    def norm(name: str) -> tuple[str, frozenset[str]]:
        if name not in norm_cache:
            norm_cache[name] = normalize(name)
        return norm_cache[name]

    out: list[Suggestion] = []
    for target in target_fields:
        if not target.get("required", False):
            continue
        t_compact, t_tokens = norm(target["name"])
        best: Suggestion | None = None
        best_rank: tuple[float, int, int] | None = None
        for sf in source_fields:
            s_compact, s_tokens = norm(sf["name"])
            score, hit, updated = 0.0, 0, 0
            match_source = None
            entry = history.get((source_key, s_compact, t_compact))
            transform = None
            if entry is not None:
                score, hit, updated = 1.0, entry["hit"], entry["updated"]
                match_source = "HISTORY"
                transform = entry["transform"]
            elif s_tokens == t_tokens and s_tokens:
                score, match_source = 1.0, "EXACT"
            else:
                sim = jaro_winkler(s_compact, t_compact)
                if sim >= threshold:
                    score, match_source = sim, "SIMILAR"
            if match_source and transform is None:
                transform = infer_transform(sf.get("samples"))
            rank = (score, hit, updated)
            if match_source and (best_rank is None or rank > best_rank):
                best = Suggestion(
                    source=sf["name"],
                    target=target["name"],
                    score=score,
                    match_source=match_source,
                    transform=transform,
                )
                best_rank = rank
        if best:
            out.append(best)
    return out


# ---- 5. 评测与统计 ----


@dataclass
class CaseResult:
    case_id: str
    name: str
    required: int
    suggested: int
    pairing_ok: int
    transform_ok: int
    full_ok: int
    details: list[str] = field(default_factory=list)


def evaluate_case(
    case: dict,
    history: dict,
    threshold: float,
) -> CaseResult:
    expected_by_target = {e["target"]: e for e in case["expected"]}
    suggestions = suggest(
        source_key(case),
        case["source"]["fields"],
        case["target"]["fields"],
        history,
        threshold,
    )
    required = [f["name"] for f in case["target"]["fields"] if f.get("required", False)]
    res = CaseResult(
        case_id=case["id"],
        name=case["name"],
        required=len(required),
        suggested=len(suggestions),
        pairing_ok=0,
        transform_ok=0,
        full_ok=0,
    )
    sug_by_target = {s.target: s for s in suggestions}
    for target_name in required:
        exp = expected_by_target.get(target_name)
        if exp is None:
            res.details.append(f"  [warn] 必填目标 {target_name} 无 ground truth")
            continue
        sug = sug_by_target.get(target_name)
        if sug is None:
            res.details.append(f"  [miss] {target_name} 无建议（期望 {exp['source']}）")
            continue
        pairing = normalize(sug.source)[0] == normalize(exp["source"])[0]
        transform = pairing and sug.transform["type"] == exp["transform"]
        full = transform
        if transform and "datePattern" in exp and "datePattern" in sug.transform:
            full = full and sug.transform["datePattern"] == exp["datePattern"]
        if transform and "constant" in exp and "constant" in sug.transform:
            full = full and str(sug.transform["constant"]) == str(exp["constant"])
        if pairing:
            res.pairing_ok += 1
        if transform:
            res.transform_ok += 1
        if full:
            res.full_ok += 1
        flag = "ok  " if full else ("~" if transform else ("P" if pairing else "X "))
        extra = ""
        if not full and transform and "datePattern" in exp and sug.transform.get("datePattern"):
            extra = f"  (日期格式 {sug.transform['datePattern']} → 期望 {exp['datePattern']})"
        res.details.append(
            f"  [{flag}] {target_name} <- {sug.source} ({sug.match_source} {sug.score:.2f}, "
            f"{sug.transform['type']})" + extra
        )
    return res


def source_key(case: dict) -> str:
    """源结构指纹：表名 / 队列名 / 类型（对应映射历史按结构隔离）。"""
    source = case["source"]
    return source.get("table") or source.get("queue") or source.get("kind")


def pct(n: int, d: int) -> float:
    return n / d if d else 0.0


def summarize(results: list[CaseResult]) -> dict:
    required = sum(r.required for r in results)
    return {
        "required": required,
        "suggested": sum(r.suggested for r in results),
        "pairing_ok": sum(r.pairing_ok for r in results),
        "transform_ok": sum(r.transform_ok for r in results),
        "full_ok": sum(r.full_ok for r in results),
        "coverage": pct(sum(r.suggested for r in results), required),
        "pairing_rate": pct(sum(r.pairing_ok for r in results), required),
        "transform_rate": pct(sum(r.transform_ok for r in results), required),
        "full_rate": pct(sum(r.full_ok for r in results), required),
    }


def print_table(results: list[CaseResult], title: str) -> None:
    print(f"\n== {title} ==")
    print(f"{'场景':<34}{'必填':>4}{'建议':>5}{'配对':>5}{'转换':>5}{'完整':>5}")
    for r in results:
        print(
            f"{r.name[:32]:<34}{r.required:>4}{r.suggested:>5}"
            f"{r.pairing_ok:>5}{r.transform_ok:>5}{r.full_ok:>5}"
        )
    s = summarize(results)
    print(
        f"{'合计':<34}{s['required']:>4}{s['suggested']:>5}{s['pairing_ok']:>5}"
        f"{s['transform_ok']:>5}{s['full_ok']:>5}"
    )
    print(
        "自动填充率(配对) {:.0%} | 转换类型正确率 {:.0%} | 参数完整率 {:.0%} | 覆盖度 {:.0%}".format(
            s["pairing_rate"], s["transform_rate"], s["full_rate"], s["coverage"]
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="字段映射自动填充率统计")
    parser.add_argument("--eval-set", default="eval-set.json", type=Path)
    parser.add_argument("--threshold", default=0.85, type=float)
    parser.add_argument("--topk", default=1, type=int)
    args = parser.parse_args()

    data = json.loads(args.eval_set.read_text(encoding="utf-8"))
    cases = data["cases"]

    # 冷启动：无历史
    cold = [evaluate_case(c, {}, args.threshold) for c in cases]
    print_table(cold, f"冷启动（无映射历史，阈值 {args.threshold}）")
    for r in cold:
        if r.details:
            print(f"  [{r.case_id}]")
            for d in r.details:
                print(d)

    # 带历史（资产沉淀）：以全部 ground truth 作为已确认映射，模拟同站点已配置过
    history = {}
    stamp = 0
    for c in cases:
        key = source_key(c)
        for e in c["expected"]:
            history[(key, normalize(e["source"])[0], normalize(e["target"])[0])] = {
                "transform": {
                    "type": e["transform"],
                    "datePattern": e.get("datePattern"),
                    "constant": e.get("constant"),
                },
                "hit": 1,
                "updated": stamp,
            }
            stamp += 1
    warm = [evaluate_case(c, history, args.threshold) for c in cases]
    print_table(warm, "带映射历史（同站点资产沉淀后）")

    cold_s = summarize(cold)
    warm_s = summarize(warm)
    print(
        "\n结论：冷启动自动填充率 {:.0%}，带历史 {:.0%}（目标 >= 80%）。".format(
            cold_s["pairing_rate"], warm_s["pairing_rate"]
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
