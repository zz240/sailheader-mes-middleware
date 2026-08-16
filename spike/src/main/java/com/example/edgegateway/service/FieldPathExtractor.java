package com.example.edgegateway.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 从样例 JSON 提取所有叶子字段路径：对象用点号连接，数组取首个元素并带 [0]。 */
public final class FieldPathExtractor {

    private FieldPathExtractor() {
    }

    public static List<String> extract(Object sample) {
        List<String> paths = new ArrayList<>();
        walk("", sample, paths);
        return paths;
    }

    private static void walk(String prefix, Object value, List<String> paths) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                walk(path, entry.getValue(), paths);
            }
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty()) {
                walk(prefix + "[0]", list.get(0), paths);
            }
        } else {
            paths.add(prefix);
        }
    }
}
