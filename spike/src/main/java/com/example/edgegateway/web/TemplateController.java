package com.example.edgegateway.web;

import com.example.edgegateway.model.Template;
import com.example.edgegateway.service.TemplateService;
import com.example.edgegateway.web.dto.TemplateRequest;
import com.example.edgegateway.web.dto.TemplateView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 模板 REST：CRUD + 按名称/版本查询 + 导出/导入。 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public List<TemplateView> list(@RequestParam(required = false) String name) {
        return templateService.list(name).stream().map(TemplateView::from).toList();
    }

    @GetMapping("/{id}")
    public TemplateView get(@PathVariable Long id) {
        return TemplateView.from(templateService.get(id));
    }

    @PostMapping
    public ResponseEntity<TemplateView> create(@RequestBody TemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(TemplateView.from(templateService.create(request)));
    }

    @PutMapping("/{id}")
    public TemplateView update(@PathVariable Long id, @RequestBody TemplateRequest request) {
        return TemplateView.from(templateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 导出：返回可直接再导入的模板 JSON（name + version + configJson）。 */
    @GetMapping("/{id}/export")
    public TemplateView export(@PathVariable Long id) {
        return TemplateView.from(templateService.get(id));
    }

    /** 导入：创建新版本（版本缺省自动 +1），不覆盖旧版本。 */
    @PostMapping("/import")
    public ResponseEntity<TemplateView> importTemplate(@RequestBody TemplateRequest request) {
        Template template = templateService.importTemplate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TemplateView.from(template));
    }
}
