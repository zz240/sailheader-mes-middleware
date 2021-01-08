package com.sailheader.middleware.rest;

import com.huaweicloud.sdk.iot.module.dto.Config;
import com.sailheader.middleware.service.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * 配置处理控制器，用于云端同步配置
 */
@RestController
@RequestMapping(value = "/configs")
@Profile("huawei-cloud")
public class ConfigController {
    private final ConfigService configService;

    @Autowired
    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @PutMapping("/{id}")
    public void setConfig(@PathVariable("id") String id, @RequestBody Config config) {
        config.setId(id);
        configService.updateConfig(config);
    }
}