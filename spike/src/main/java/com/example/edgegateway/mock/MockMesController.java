package com.example.edgegateway.mock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mock/mes")
public class MockMesController {

    private final List<Map<String, Object>> received =
            Collections.synchronizedList(new ArrayList<>());

    @PostMapping("/purchase-order")
    public Map<String, Object> receive(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        System.out.println("[mock-mes] auth=" + auth + " body=" + body);
        received.add(body);
        return Map.of("code", "0", "message", "ok");
    }

    @GetMapping("/received")
    public List<Map<String, Object>> received() {
        return new ArrayList<>(received);
    }
}
