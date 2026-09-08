package com.example.edgegateway.mapper;

import com.example.edgegateway.support.H2MapperTest;
import com.example.edgegateway.model.Watermark;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@H2MapperTest
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
