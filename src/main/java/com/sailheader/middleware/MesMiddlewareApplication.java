package com.sailheader.middleware;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(basePackages ="com.sailheader.middleware.mapper")
public class MesMiddlewareApplication {

    public static void main(String[] args) {
        SpringApplication.run(MesMiddlewareApplication.class, args);
    }

}
