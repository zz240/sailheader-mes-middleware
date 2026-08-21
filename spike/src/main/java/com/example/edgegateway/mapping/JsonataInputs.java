package com.example.edgegateway.mapping;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进入 JSONata 引擎前的输入规范化：dashjoin 只接受 JSON 类型（值/Map/List），
 * READ_SQL 行里的 JDBC 日期类型（LocalDateTime/Timestamp 等）必须转成字符串，
 * 否则求值直接报 "Only JSON types ... allowed as input"（本地实测发现）。
 */
public final class JsonataInputs {

    private JsonataInputs() {
    }

    /** 递归把非 JSON 类型转换为 JSON 安全值：日期/时间 → ISO-8601 字符串，byte[] → Base64，其他 → toString。 */
    public static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JsonataInputs::normalize).toList();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof Date d) {
            return d.toString();
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return String.valueOf(value);
    }
}
