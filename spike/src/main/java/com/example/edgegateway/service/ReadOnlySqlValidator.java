package com.example.edgegateway.service;

import com.example.edgegateway.config.datasource.JdbcDataSourceConfig;
import com.example.edgegateway.service.sql.SqlDialects;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 只读 SQL 真校验：把 SQL 包一层「不取数」的壳执行，验证语法与表/列存在。
 * {watermark} 占位符替换为恒真谓词；只读、不产生数据变更。
 *
 * <p>壳的写法归方言（MySQL 的 {@code ... LIMIT 0} / SQL Server 的 {@code SELECT TOP 0 ...}，ADR-0014），
 * 方言从数据源类型取——所以这里不需要知道连的是哪种库。</p>
 */
@Component
public class ReadOnlySqlValidator {

    public void validate(String url, JdbcDataSourceConfig config, String sql) {
        String probeSql = SqlDialects.of(config.type())
                .validateProbe(sql.replace("{watermark}", "1=1"));
        try (Connection conn = DriverManager.getConnection(url, config.username(), config.password());
             Statement st = conn.createStatement()) {
            st.execute(probeSql);
        } catch (Exception e) {
            throw new IllegalStateException("SQL 执行校验失败: " + e.getMessage(), e);
        }
    }
}
