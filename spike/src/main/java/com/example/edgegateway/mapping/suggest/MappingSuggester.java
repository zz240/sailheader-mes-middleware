package com.example.edgegateway.mapping.suggest;

import java.util.List;

/**
 * 映射建议器接口（纯函数）：输入源结构、目标字段清单与映射历史，
 * 输出按得分排序的建议列表。规则引擎为默认实现，LLM 实现可同接口替换。
 */
public interface MappingSuggester {

    List<MappingSuggestion> suggest(
            List<SourceField> sourceFields,
            List<TargetField> targetFields,
            List<HistoryMapping> history
    );
}
