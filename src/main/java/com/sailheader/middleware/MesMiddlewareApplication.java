package com.sailheader.middleware;

import com.huaweicloud.sdk.iot.module.ItClient;
import com.huaweicloud.sdk.iot.module.exception.GeneraException;
import com.sailheader.middleware.config.SpringApplicationContext;
import com.sailheader.middleware.service.JobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MesMiddlewareApplication {
    private static ItClient itClient;

    @Autowired
    private JobService jobService;

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
