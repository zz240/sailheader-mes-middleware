package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionType;

import java.util.Map;

/** 动作接口响应体。 */
public record ActionView(
        Long id,
        Long jobId,
        String name,
        ActionType type,
        Integer index,
        Map<String, Object> addition
) {

    public static ActionView from(Action action) {
        return new ActionView(
                action.getId(),
                action.getJobId(),
                action.getName(),
                action.getType(),
                action.getActionIndex(),
                action.getAddition());
    }
}
