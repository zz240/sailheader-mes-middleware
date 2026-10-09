package com.example.edgegateway.service;

import org.apache.camel.Exchange;

/**
 * 把 exchange 的 body 读成**原始文本**（#145 从 {@code RouteGenerator} 搬出的那一段）。
 *
 * <p>出站那两条路都要用：链上 HTTP 动作捕获响应（{@link ReadWriteFragments#captureResponse}）与
 * 打包重发收尾配对（{@link RestoreFragment#settleBatchResend}）。走 Camel 的类型转换
 * （InputStream / StreamCache / byte[] 都能读），读不出来就立即失败——不留一个"看起来成功、
 * 其实没捕获到"的步骤。</p>
 */
final class ResponseTexts {

    private ResponseTexts() {
    }

    static String text(Exchange ex) {
        Object body = ex.getMessage().getBody();
        if (body == null) {
            return null;
        }
        try {
            return ex.getContext().getTypeConverter().mandatoryConvertTo(String.class, ex, body);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 响应体读不出文本，无法捕获: " + e.getMessage(), e);
        }
    }
}
