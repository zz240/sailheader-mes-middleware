package com.example.edgegateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退：非 API/静态资源路径转发到 index.html，刷新页面不 404。
 * /api 与 /mock 由各自控制器优先匹配，不受影响。
 */
@Controller
public class SpaController {

    @GetMapping(value = {"/", "/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String forward() {
        return "forward:/index.html";
    }
}
