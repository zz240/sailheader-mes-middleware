package com.example.edgegateway.web.dto;

import java.util.List;

/** 任务级配置校验结果：errors 阻断（valid=false），warnings 仅提示。 */
public record ValidationResult(
        boolean valid,
        List<Issue> errors,
        List<Issue> warnings
) {

    public record Issue(String scope, String field, String message) {
    }
}
