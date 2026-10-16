package com.example.edgegateway.config.action;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 调用动作：数据源（根地址）+ path + method + bodySourceStep（默认上一个动作输出），
 * 外加**成功条件**两格（#148）。
 *
 * <p>成功条件是为「HTTP 永远 200、业务结果写在响应体里」那类接口约定设的：
 * {@code successPath} 在响应体里取值、{@code successExpect} 是期望值。两格都留空即不启用断言
 * （与没有这个功能时完全一致）；只填 {@code successExpect} 是配置错误——不知道该看响应体的哪里。</p>
 *
 * <p>字段名刻意叫 {@code successPath} / {@code successExpect} 而不是 {@code errorCode}：
 * 网关不知道哪家的哪个字段代表成功，由配置声明；判定见 {@code service/HttpSuccessAssert}。</p>
 */
public record HttpCallConfig(
        Long dataSourceId,
        String path,
        String method,
        Integer bodySourceStep,
        String successPath,
        String successExpect
) implements ActionConfig {

    public static HttpCallConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "path", "method", "bodySourceStep",
                "successPath", "successExpect"));
        Object id = map.get("dataSourceId");
        return new HttpCallConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("path")),
                ConfigValues.str(map.get("method")),
                map.get("bodySourceStep") == null ? null : Integer.valueOf(map.get("bodySourceStep").toString()),
                ConfigValues.str(map.get("successPath")),
                ConfigValues.str(map.get("successExpect")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.HTTP_CALL;
    }

    @Override
    public void validate() {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("HTTP 调用动作需要 dataSourceId");
        }
        // #148：期望值必须跟一个取值路径一起用——只有期望值的话，运行期不知道该去响应体哪里取值
        if (isBlank(successPath) && !isBlank(successExpect)) {
            throw new IllegalArgumentException("成功条件需要 successPath（successExpect 要跟一个取值路径一起用）");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataSourceId", dataSourceId);
        if (path != null) {
            map.put("path", path);
        }
        if (method != null) {
            map.put("method", method);
        }
        if (bodySourceStep != null) {
            map.put("bodySourceStep", bodySourceStep);
        }
        // 只写非空字段：前端把两格清空后保存，addition 里就不该出现它们（与既有口径一致）
        if (!isBlank(successPath)) {
            map.put("successPath", successPath);
        }
        if (!isBlank(successExpect)) {
            map.put("successExpect", successExpect);
        }
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
