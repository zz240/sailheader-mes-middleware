package com.example.edgegateway.mapping.suggest;

/** 一条已确认的映射历史（调用方负责按站点+方向过滤后传入）。 */
public record HistoryMapping(String sourcePath, String targetName) {
}
