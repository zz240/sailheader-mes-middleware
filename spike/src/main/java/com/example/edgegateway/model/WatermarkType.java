package com.example.edgegateway.model;

/**
 * READ_SQL 水位策略（#79）：两种场景明确、可切换。
 *
 * <ul>
 *   <li>{@code SEQUENCE}＝增量（id）：谓词 {@code col > last}，水位取批内 max(id)，适合"源只增/少更新"。</li>
 *   <li>{@code TIMESTAMP}＝刷量/全量（按更新时间 upsert）：谓词 {@code col >= last}（含边界），
 *       水位取批内 max(updated_at)（<b>不用墙钟 now</b>），配合下游业务键 upsert，幂等、无边界漏单、可安全重跑。</li>
 * </ul>
 */
public enum WatermarkType {
    TIMESTAMP,
    SEQUENCE
}
