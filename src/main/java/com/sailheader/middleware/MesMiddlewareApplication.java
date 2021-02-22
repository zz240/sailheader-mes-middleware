package com.sailheader.middleware;

import com.huaweicloud.sdk.iot.module.ItClient;
import com.huaweicloud.sdk.iot.module.exception.GeneraException;
import com.sailheader.middleware.config.SpringApplicationContext;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;


@SpringBootApplication
@MapperScan(basePackages = "com.sailheader.middleware.mapper", sqlSessionTemplateRef = "local.data.source.session.template")
public class MesMiddlewareApplication {
    private static ItClient itClient;

    @Bean
    public ItClient getItClient() {
        return itClient;
    }

    public static void main(String[] args) {
        try {
            itClient = ItClient.createFromEnv();
            itClient.open();
        } catch (GeneraException e) {
            e.printStackTrace();
        }
        SpringApplicationContext.setApplicationContext(SpringApplication.run(MesMiddlewareApplication.class, args));
    }



}
