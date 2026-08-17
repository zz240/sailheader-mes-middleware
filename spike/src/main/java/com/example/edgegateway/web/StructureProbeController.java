package com.example.edgegateway.web;

import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.service.DataSourceService;
import com.example.edgegateway.service.StructureProbeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 结构探测端点：连库探测（按数据源）+ 样例兜底。 */
@RestController
@RequestMapping("/api")
public class StructureProbeController {

    private final StructureProbeService probeService;
    private final DataSourceService dataSourceService;

    public StructureProbeController(StructureProbeService probeService, DataSourceService dataSourceService) {
        this.probeService = probeService;
        this.dataSourceService = dataSourceService;
    }

    /** POST /api/data-sources/{id}/probe：连库探测；失败且带样例时回退样例模式。 */
    @PostMapping("/data-sources/{id}/probe")
    public SourceStructure probe(@org.springframework.web.bind.annotation.PathVariable Long id,
                                 @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        String table = request.get("table") == null ? null : request.get("table").toString();
        boolean refresh = Boolean.TRUE.equals(request.get("refresh"));
        DataSource dataSource = dataSourceService.get(id);
        try {
            return probeService.probeFromDb(dataSource, table, refresh);
        } catch (IllegalStateException e) {
            // 连库失败且有样例时回退样例模式（对应 #10 决策）
            if (request.containsKey("sample")) {
                return probeService.probeSample(request.get("sample"));
            }
            throw e;
        }
    }

    /** GET /api/data-sources/{id}/suggest-table：从任务读 SQL 推断表名，供探测预填。 */
    @GetMapping("/data-sources/{id}/suggest-table")
    public Map<String, Object> suggestTable(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return Map.of("table", probeService.suggestTable(id));
    }

    /** POST /api/probe/sample：贴样例 JSON，递归展开叶子字段（含类型与样例值）。 */
    @PostMapping("/probe/sample")
    public SourceStructure probeSample(@RequestBody Map<String, Object> body) {
        return probeService.probeSample(body.get("sample"));
    }
}
