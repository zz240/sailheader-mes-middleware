package com.example.edgegateway.mapping.suggest;

/** 建议来源标签：历史复用 / 归一化精确 / Jaro-Winkler 相似。 */
public enum MatchSource {
    HISTORY,
    EXACT,
    SIMILAR,
    LLM,
    /** 源/目标数组结构匹配（S4 组建议）。 */
    ARRAY
}
