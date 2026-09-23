package com.example.edgegateway;

import com.example.edgegateway.mapper.WatermarkMapper;
import com.example.edgegateway.model.Watermark;
import org.springframework.stereotype.Component;

/**
 * 水位表（产品库 {@code watermark}）用 MyBatis-Plus 读写：与配置那套同一个持久化口径。
 *
 * <p>写用 {@link WatermarkMapper#upsert} 的**一条语句 upsert**，不是「先查后写」：`job_key` 是主键，
 * 同一任务被手动与调度同时触发时，先查后写在两条语句之间会给撞主键留窗口。</p>
 */
@Component
public class MybatisWatermarkStore implements WatermarkStore {

    private final WatermarkMapper mapper;

    public MybatisWatermarkStore(WatermarkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long get(String key) {
        Watermark row = mapper.selectById(key);
        return row == null || row.getWmValue() == null ? 0L : row.getWmValue();
    }

    @Override
    public void put(String key, long value) {
        Watermark row = new Watermark();
        row.setJobKey(key);
        row.setWmValue(value);
        mapper.upsert(row);
    }
}
