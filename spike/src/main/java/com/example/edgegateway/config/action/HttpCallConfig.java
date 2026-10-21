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
        String successExpect,
        Integer timeoutMs
) implements ActionConfig {

    public static HttpCallConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "path", "method", "bodySourceStep",
                "successPath", "successExpect", "timeoutMs"));
        Object id = map.get("dataSourceId");
        return new HttpCallConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("path")),
                ConfigValues.str(map.get("method")),
                map.get("bodySourceStep") == null ? null : Integer.valueOf(map.get("bodySourceStep").toString()),
                ConfigValues.str(map.get("successPath")),
                ConfigValues.str(map.get("successExpect")),
                map.get("timeoutMs") == null ? null : Integer.valueOf(map.get("timeoutMs").toString()));
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
        // #148：期望值必须跟一个取值路径一起用——规则本体在下面的静态入口里，写时与运行期共用同一份
        requireSuccessPathWithExpect(successPath, successExpect);
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new IllegalArgumentException("HTTP 调用动作的 timeoutMs 必须是正整数（单位毫秒）");
        }
    }

    /**
     * 成功条件两格的自洽规则（#148）：留空 {@code successPath} 就不许单独写 {@code successExpect}——
     * 只有期望值，运行期不知道该去响应体的哪里取值。
     *
     * <p>规则的**唯一一份实现**在这里（配置类自己说了算自己的合法性；运行期只能反过来依赖它），两个点共用：
     * 写端点的 {@link #validate()}（{@code ActionService.add/update} → 400）与运行期的
     * {@code service/HttpSuccessAssert}。后者的闸不能删：规则若从别的路进了数据库（老行 / 导入），
     * 运行期必须显式失败而不是静默放行；两处共用同一个条件与同一句错误信息，也不会各自漂移。</p>
     */
    public static void requireSuccessPathWithExpect(String successPath, String successExpect) {
        if (isBlank(successPath) && !isBlank(successExpect)) {
            throw new IllegalArgumentException("成功条件需要 successPath（successExpect 要跟一个取值路径一起用）");
        }
    }

    /** 空白文本判定：配置期与运行期（{@code service/HttpSuccessAssert}）共用这一份。 */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
        if (timeoutMs != null) {
            map.put("timeoutMs", timeoutMs);
        }
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
