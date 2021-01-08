package com.sailheader.middleware.service;

import com.huaweicloud.sdk.iot.module.ItClient;
import com.huaweicloud.sdk.iot.module.dto.Config;
import com.huaweicloud.sdk.iot.module.exception.GeneraException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置管理服务
 */
@Service
@Profile("huawei-cloud")
public class ConfigService {
    private final ItClient itClient;

    private Map<String, Config> configMap = new ConcurrentHashMap<>();

    @Autowired
    public ConfigService(ItClient itClient) {
        this.itClient = itClient;
    }

    // 类装载时会执行一次
    public void syncConfigs() {
        //TODO 先从本地加载配置项

        //从云端同步配置
        try {
            List<Config> configs = itClient.syncConfigs();
            configs.forEach(config -> configMap.put(config.getId(), config));
            //TODO 持久化保存配置

            //向API GW确认已同步的配置
            itClient.confirmConfigs(configs);
        } catch (GeneraException e) {
            System.out.println(e.getMessage());
        }
    }

    public void updateConfig(Config config) {
        configMap.put(config.getId(), config);
    }
}
