package com.example.edgegateway.prototype;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PROTOTYPE —— 源结构探测原型（throwaway，只用于定契约，勿进生产）。
 *
 * <p>回答：连库探测 + 样例兜底返回什么结构、何时回退。
 * 统一输出：{source, fields:[{name, type, nullable, samples}]}。</p>
 */
public class StructureProbePrototype {

    /** 连库探测：查前 limit 行 + ResultSetMetaData，产出字段名/类型/是否可空/样例值。 */
    public static Map<String, Object> probeDb(String url, String username, String password,
                                              String table, int limit) {
        List<Map<String, Object>> fields = new ArrayList<>();
        String source = "db";
        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT " + limit)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            List<List<Object>> samples = new ArrayList<>();
            for (int i = 0; i < cols; i++) {
                samples.add(new ArrayList<>());
            }
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    Object value = rs.getObject(i);
                    if (value != null && !samples.get(i - 1).contains(value)) {
                        samples.get(i - 1).add(value);
                    }
                }
            }
            for (int i = 1; i <= cols; i++) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", meta.getColumnLabel(i));
                field.put("type", meta.getColumnTypeName(i));
                field.put("nullable", meta.isNullable(i) != ResultSetMetaData.columnNoNulls);
                field.put("samples", samples.get(i - 1));
                fields.add(field);
            }
        } catch (Exception e) {
            // 连不上/无权限/非 MySQL → 由调用方决定回退样例模式
            source = "db-error:" + e.getMessage();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("fields", fields);
        return out;
    }

    /** 样例兜底：递归展开叶子字段，类型从样例值粗推断，nullable 未知标 true。 */
    public static Map<String, Object> probeSample(Object sample) {
        List<Map<String, Object>> fields = new ArrayList<>();
        walk("", sample, fields);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "sample");
        out.put("fields", fields);
        return out;
    }

    private static void walk(String prefix, Object value, List<Map<String, Object>> fields) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String path = prefix.isEmpty()
                        ? entry.getKey().toString()
                        : prefix + "." + entry.getKey();
                walk(path, entry.getValue(), fields);
            }
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty()) {
                walk(prefix + "[0]", list.get(0), fields);
            }
        } else {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", prefix);
            field.put("type", value == null ? "NULL" : simpleType(value));
            field.put("nullable", true); // 样例无法判断可空性
            field.put("samples", value == null ? List.of() : List.of(value));
            fields.add(field);
        }
    }

    private static String simpleType(Object value) {
        if (value instanceof Number) {
            return "NUMBER";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        return "STRING";
    }
}
