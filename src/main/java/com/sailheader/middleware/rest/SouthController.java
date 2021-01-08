package com.sailheader.middleware.rest;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 南向接口测试
 */

@RestController
@RequestMapping("/huaweicloud/south")
@Profile("huawei-cloud")
public class SouthController {

    @GetMapping("/test/{name}")
    public String getMsg(@PathVariable String name) {
        System.out.println(name);
        return "南向访问成功!";
    }
}
