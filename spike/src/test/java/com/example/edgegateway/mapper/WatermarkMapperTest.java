package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.edgegateway.model.Watermark;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisPlusTest
@Sql("/schema.sql")
class WatermarkMapperTest {

    @Autowired
    private WatermarkMapper watermarkMapper;

    @Test
    void savesAndReadsWatermarkByKey() {
        Watermark wm = new Watermark();
        wm.setJobKey("1:po_order");
        wm.setWmValue(42L);
        watermarkMapper.insert(wm);

        Watermark found = watermarkMapper.selectById("1:po_order");
        assertThat(found.getWmValue()).isEqualTo(42L);
    }
}
