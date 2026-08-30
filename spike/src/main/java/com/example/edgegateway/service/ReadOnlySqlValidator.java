package com.example.edgegateway.service;

import com.example.edgegateway.config.MysqlDataSourceConfig;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 只读 SQL 真校验：把 SQL 包一层 SELECT ... LIMIT 0 执行，验证语法与表/列存在。
 * {watermark} 占位符替换为恒真谓词；只读、不产生数据变更。
 */
@Component
public class ReadOnlySqlValidator {

    public void validate(String url, MysqlDataSourceConfig config, String sql) {
        String probeSql = "SELECT * FROM ( " + sql.replace("{watermark}", "1=1") + " ) __probe LIMIT 0";
        try (Connection conn = DriverManager.getConnection(url, config.username(), config.password());
             Statement st = conn.createStatement()) {
            st.execute(probeSql);
        } catch (Exception e) {
            throw new IllegalStateException("SQL 执行校验失败: " + e.getMessage(), e);
        }
    }
}
