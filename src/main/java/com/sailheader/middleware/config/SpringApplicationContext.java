package com.sailheader.middleware.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import javax.annotation.PostConstruct;

@Configuration
public class SpringApplicationContext {
    @Getter
    @Setter
    private static ApplicationContext applicationContext;

}
