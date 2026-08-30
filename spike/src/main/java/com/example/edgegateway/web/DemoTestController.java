package com.example.edgegateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoTestController {

    private static final Logger log = LoggerFactory.getLogger(DemoTestController.class);

    @PostMapping("/bom")
    public void bom(@RequestBody Map<String,Object> map) {
        log.info("test-bom:{}", map);
    }
}
