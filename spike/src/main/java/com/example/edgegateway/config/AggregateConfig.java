package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 合并动作（#142 / ADR-0017 的 R3）：攒住一批消息，**一次**请求发出去，响应按配对的键扇回各自的子消息。
 *
 * <p>配置六格，对应票面那六个字段：</p>
 * <ul>
 *   <li><b>数据源</b> {@code dataSourceId}：发到哪儿去（HTTP 型数据源，与 {@code HTTP_CALL} 同一套）；</li>
 *   <li><b>路径</b> {@code path}：拼在数据源地址后的那段；</li>
 *   <li><b>方法</b> {@code method}：缺省 {@code POST}——合并出去的是一份报文，只认带 body 的那几个方法；</li>
 *   <li><b>请求体字段名</b> {@code bodyField}：整批包成 {@code {"<bodyField>": [ …每条消息自己那一片… ]}}；</li>
 *   <li><b>响应里用来配对的字段</b> {@code resultKeyField}：响应每一项里这个字段的值，
 *       与消息的**业务键**相等时就算配上了（一条消息一个业务键，配对键只能取自它，ADR-0017 决策 9）；</li>
 *   <li><b>配对结果塞进子消息 body 的键</b> {@code attachAs}：配对到的那一份写进 body 的这个键，
 *       紧随其后的写库动作直接用（票面用户故事 8）。</li>
 * </ul>
 *
 * <p><b>刻意没有的配置</b>：没有「条数阈值」也没有「超时」——攒齐的边界是**上游读取批读完那一刻**
 * （ADR-0017 决策 3 的落地：绑上游读取批），要限制单次发送条数就调读库那一步的批大小，一处生效；
 * 超时只当失败判据、不当发送触发，MVP 先不做（卡住就是卡住，任务挂在那里用户看得见）。</p>
 */
public record AggregateConfig(
        Long dataSourceId,
        String path,
        String method,
        String bodyField,
        String resultKeyField,
        String attachAs
) implements ActionConfig {

    /** 缺省方法：合并出去的是报文，POST 是最常见的那一个。 */
    public static final String DEFAULT_METHOD = "POST";

    /** 只认带 body 的方法：GET/DELETE 装不下「整批报文」这件事（配置期就拦下，别到运行期才发现）。 */
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH");

    /** 字段名（body 里的键 / 响应里的键 / body 上要落的键）：纯名字段，不认路径与下标。 */
    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*";

    public static AggregateConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "path", "method", "bodyField", "resultKeyField", "attachAs"));
        Object id = map.get("dataSourceId");
        String method = ConfigValues.str(map.get("method"));
        return new AggregateConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("path")),
                method == null || method.isBlank() ? DEFAULT_METHOD : method.trim().toUpperCase(),
                ConfigValues.str(map.get("bodyField")),
                ConfigValues.str(map.get("resultKeyField")),
                ConfigValues.str(map.get("attachAs")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.AGGREGATE;
    }

    @Override
    public void validate() {
        if (dataSourceId == null || dataSourceId <= 0) {
            throw new IllegalArgumentException("合并动作需要 dataSourceId（发到哪个 HTTP 数据源）");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("合并动作需要 path（拼在数据源地址后面的那段）");
        }
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException(
                    "合并动作的方法只认 " + METHODS + "（要把整批报文发出去，得是带 body 的那几个）: " + method);
        }
        requireName(bodyField, "bodyField", "请求体字段名（整批包成 {\"<bodyField>\": [ … ]}）");
        requireName(resultKeyField, "resultKeyField", "响应里用来配对的字段（与消息的业务键相等就算配上）");
        requireName(attachAs, "attachAs", "配对结果塞进子消息 body 的哪个键");
    }

    private static void requireName(String value, String field, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("合并动作需要 " + field + "（" + what + "）");
        }
        if (!value.matches(NAME)) {
            throw new IllegalArgumentException(
                    field + " 应为纯字段名（如 " + field + " 对应的那个键），不要写路径或数组下标: " + value);
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataSourceId", dataSourceId);
        map.put("path", path);
        map.put("method", method);
        map.put("bodyField", bodyField);
        map.put("resultKeyField", resultKeyField);
        map.put("attachAs", attachAs);
        return map;
    }

    private static void rejectUnknownKeys(Map<String, Object> map, Set<String> allowed) {
        map.keySet().stream()
                .filter(k -> !allowed.contains(k))
                .findFirst()
                .ifPresent(k -> {
                    throw new IllegalArgumentException("未知字段: " + k);
                });
    }
}
