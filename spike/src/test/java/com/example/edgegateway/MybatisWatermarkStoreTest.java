package com.example.edgegateway;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.edgegateway.mapper.WatermarkMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 水位读写走产品库那套（MyBatis-Plus）：缺行读作 0，写是**一条语句的原子 upsert**（同 key 覆盖，不新增行）。
 *
 * <p>H2 显式开 {@code MODE=MySQL}：upsert 用的是 MySQL 的 {@code on duplicate key update}（产品库就是 MySQL）。
 * 切片默认会把数据源换成内嵌 H2（普通模式，不认这条 SQL），所以这里 {@code Replace.NONE} 用下面这个 URL。</p>
 */
@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:watermarkStore;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="})
@Sql("/schema.sql")
@Import(MybatisWatermarkStore.class)
class MybatisWatermarkStoreTest {

    @Autowired
    private WatermarkStore store;

    @Autowired
    private WatermarkMapper watermarkMapper;

    @Test
    void missingKeyReadsAsZero() {
        assertThat(store.get("job:404")).isZero();
    }

    @Test
    void putRoundTripsAndOverwritesTheSameKey() {
        store.put("job:1", 42L);
        assertThat(store.get("job:1")).isEqualTo(42L);

        // 同一任务第二次推进水位：覆盖那一行，不是插出第二行（job_key 是主键）
        store.put("job:1", 43L);
        assertThat(store.get("job:1")).isEqualTo(43L);
        assertThat(watermarkMapper.selectCount(null)).as("水位表里只有这一行").isEqualTo(1L);
    }
}
