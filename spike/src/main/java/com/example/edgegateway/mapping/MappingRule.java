package com.example.edgegateway.mapping;

import java.util.Map;

public record MappingRule(
        String source,
        String target,
        TransformType type,
        String constant,
        Map<String, Object> enumMap,
        String datePattern
) {
}
