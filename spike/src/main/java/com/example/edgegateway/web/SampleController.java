package com.example.edgegateway.web;

import com.example.edgegateway.service.FieldPathExtractor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 样例字段拉取接口：贴一段样例 JSON，返回可用的字段路径列表，供映射表格点选。 */
@RestController
@RequestMapping("/api/sample-fields")
public class SampleController {

    @PostMapping
    public List<String> extract(@RequestBody Map<String, Object> body) {
        return FieldPathExtractor.extract(body.get("sample"));
    }
}
