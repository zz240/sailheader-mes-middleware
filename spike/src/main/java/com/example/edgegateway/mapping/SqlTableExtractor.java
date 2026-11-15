package com.example.edgegateway.mapping;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 表名提取：从 READ_SQL 的查询语句里认出主表名。
 *
 * <p>结构探测（{@code StructureProbeService}）与配置校验（{@code JobValidationService}）
 * 都要从任务里那条 SELECT 推断表名，提取口径只有这一处。</p>
 */
public final class SqlTableExtractor {

    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?i)\\bfrom\\s+[`\"\\[]?([a-zA-Z0-9_$.`\"\\[\\]]+)");

    private SqlTableExtractor() {
    }

    public static String extractTable(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = FROM_TABLE.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1).replaceAll("[`\"\\[\\]]", "");
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }
}
