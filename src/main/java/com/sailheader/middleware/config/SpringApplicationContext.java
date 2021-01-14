package com.sailheader.middleware.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationContext;

public class SpringApplicationContext {
    @Getter
    @Setter
    private static ApplicationContext applicationContext;

}
