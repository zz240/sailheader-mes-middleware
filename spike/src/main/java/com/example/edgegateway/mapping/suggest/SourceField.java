package com.example.edgegateway.mapping.suggest;

import java.util.List;

/**
 * 源结构中的单个字段（来自结构探测契约）。
 *
 * @param name    字段路径，如 order_no、items[0].name
 * @param type    探测到的类型名（展示用，匹配不依赖它）
 * @param nullable 是否可空
 * @param samples 原始样例行（含重复值，供转换推断）
 */
public record SourceField(String name, String type, boolean nullable, List<Object> samples) {
}
