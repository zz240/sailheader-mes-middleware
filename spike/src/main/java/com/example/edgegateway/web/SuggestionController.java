package com.example.edgegateway.web;

import com.example.edgegateway.mapping.suggest.MappingSuggestion;
import com.example.edgegateway.service.MappingSuggestionService;
import com.example.edgegateway.web.dto.SuggestionRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 映射建议端点：前端传入任务 + 源结构 + 目标字段清单，返回 top3 候选。 */
@RestController
@RequestMapping("/api/mapping-suggestions")
public class SuggestionController {

    private final MappingSuggestionService suggestionService;

    public SuggestionController(MappingSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @PostMapping
    public List<MappingSuggestion> suggest(@RequestBody SuggestionRequest request) {
        return suggestionService.suggest(request);
    }
}
