package com.example.edgegateway;

/**
 * 增量水位的读写接缝：读上次位置、每批成功后推进（{@code job_key} → 位置）。
 *
 * <p>实现走产品库那一套（MyBatis-Plus，见 {@link MybatisWatermarkStore}）；路由只认这两个方法，
 * 测试也就有地方替身（{@code mock(WatermarkStore.class)}）。</p>
 *
 * <p>注意与**按水位读业务数据**分开：那次读打的是客户侧数据源、跑的是用户配的 SQL（`RouteGenerator.readBatch`
 * 里的 {@code JdbcTemplate}），与这里不是一回事。</p>
 */
public interface WatermarkStore {

    /** 上次同步到的位置；这一格还没写过时是 0。 */
    long get(String key);

    /** 推进水位：同 key 覆盖（一条语句的原子 upsert，缺行即插）。 */
    void put(String key, long value);
}
