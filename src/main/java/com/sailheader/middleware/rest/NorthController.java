package com.sailheader.middleware.rest;

import com.sailheader.middleware.service.NorthService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * 模拟北向接口调用
 * postman填写请求模仿第三方应用 访问 APIG 代理的线上的接口
 */
@RestController
@RequestMapping("/huaweicloud/north")
@Profile("huawei-cloud")
public class NorthController {

    private NorthService northService;

    public NorthController(NorthService northService) {
        this.northService = northService;
    }

    @PostMapping(value = "/test",params = "url")
    public String sendMsgTest(@RequestParam String url, @RequestBody String body) throws Exception {
        System.out.println(url);
        System.out.println(body);
        return northService.sendMsg(url, body);
    }
}
