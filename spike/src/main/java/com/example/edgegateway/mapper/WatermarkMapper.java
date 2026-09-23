package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edgegateway.model.Watermark;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WatermarkMapper extends BaseMapper<Watermark> {

    /**
     * 推进水位：一条语句完成「缺行即插、有行即改」（产品库是 MySQL）。
     *
     * <p>不用 {@code updateById}/{@code insert} 两次调用，也不用 {@code saveOrUpdate}（先查后写）：
     * {@code job_key} 是主键，同一任务被手动与调度同时触发时，两次调用之间会撞主键。</p>
     */
    @Insert("insert into watermark(job_key, wm_value) values (#{jobKey}, #{wmValue}) "
            + "on duplicate key update wm_value = values(wm_value)")
    int upsert(Watermark watermark);
}
