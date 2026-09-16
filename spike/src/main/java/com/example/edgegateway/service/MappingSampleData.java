package com.example.edgegateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 来源字段样例的原文 → 样例数据：**一处**口径，两个读者共用（#115 / #118 / #119 / #116）。
 *
 * <p>读者一：编辑器推字段清单（粘进来的 JSON、库里存过的那份、运行快照那条路都从这儿过）。
 * 读者二：试算（#116）——它要拿真实的那份数据去跑表达式，而不是推出来的字段清单。</p>
 *
 * <p>两处必须同一份实现：清单是**这份数据**的纯函数，试算跑的也必须是**这份数据**，否则界面上左边
 * 写着一个字段、右边跑的是另一份东西。</p>
 */
public final class MappingSampleData {

    private MappingSampleData() {
    }

    /**
     * 解析结果。
     *
     * @param value            要推清单 / 要拿去跑的那份数据（对象）
     * @param sampledFirstItem 这份原文是一整个数组、只取了第 1 条（界面据此注明「按第 1 条推的」）
     */
    public record Parsed(Object value, boolean sampledFirstItem) {
    }

    /**
     * 样例原文 → 样例数据。
     *
     * <p>收对象或数组；数组按**第 1 条**算（整份数组直接用的话，推出来的路径会多一层没有名字的
     * `[0].` 前缀，编辑器认不出它属于哪个数组）。解析不了或用不上时给出能照着改的说法——这几句话
     * 会原样显示在粘贴框里，措辞是 #115 起就定下的。</p>
     */
    public static Parsed parse(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("还没有粘 JSON");
        }
        Object sample;
        try {
            sample = objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("这段 JSON 解析不了: " + e.getOriginalMessage());
        }
        if (sample instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new IllegalArgumentException("这个数组里一条数据都没有，推不出字段");
            }
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("数组里第 1 条不是一个对象，推不出字段");
            }
            return new Parsed(first, true);
        }
        if (!(sample instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("收对象或数组：粘一份 JSON，例如 {\"order_no\":\"PO-1\"}");
        }
        return new Parsed(sample, false);
    }

    /**
     * 样例数据 → 一段原文（试算要把它原样发回来跑）。
     *
     * <p>运行快照那条路本来只有解析后的对象，编辑器手里要有原文才带得动试算（#116），所以这里把解析
     * 出来的那份再写回一段 JSON。写不出来（理论上不该发生）给 null，界面上那一栏就当没有样例。</p>
     */
    public static String write(ObjectMapper objectMapper, Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
