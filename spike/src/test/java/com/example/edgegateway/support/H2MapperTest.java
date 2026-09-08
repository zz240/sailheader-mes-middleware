package com.example.edgegateway.support;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 真库切片测试的统一配置：内嵌 H2 + 测试用 {@code schema.sql} 建表。
 * Flyway 迁移面向 MySQL（V1 内联 INDEX），切片上下文必须关掉，否则上下文起不来。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@MybatisPlusTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Sql("/schema.sql")
public @interface H2MapperTest {
}
