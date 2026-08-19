package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.TemplateMapper;
import com.example.edgegateway.model.Template;
import com.example.edgegateway.web.dto.TemplateRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 模板业务：按 name + version 唯一；版本缺省时按同名最大数字版本 +1；
 * 导入即创建新版本，绝不覆盖旧版本。
 */
@Service
public class TemplateService {

    private final TemplateMapper templateMapper;

    public TemplateService(TemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    public Template create(TemplateRequest request) {
        validate(request);
        Template template = new Template();
        template.setName(request.name().trim());
        template.setVersion(resolveVersion(request));
        template.setConfigJson(request.configJson());
        ensureNoConflict(template.getName(), template.getVersion(), null);
        templateMapper.insert(template);
        return template;
    }

    /** 导入 = 创建新版本（版本缺省自动 +1），不覆盖旧版本。 */
    public Template importTemplate(TemplateRequest request) {
        return create(request);
    }

    public Template update(Long id, TemplateRequest request) {
        Template template = get(id);
        validate(request);
        template.setName(request.name().trim());
        template.setVersion(request.version() == null || request.version().isBlank()
                ? template.getVersion()
                : request.version().trim());
        template.setConfigJson(request.configJson());
        ensureNoConflict(template.getName(), template.getVersion(), id);
        templateMapper.updateById(template);
        return template;
    }

    public void delete(Long id) {
        get(id);
        templateMapper.deleteById(id);
    }

    public Template get(Long id) {
        Template template = templateMapper.selectById(id);
        if (template == null) {
            throw new NoSuchElementException("模板不存在: " + id);
        }
        return template;
    }

    public List<Template> list(String name) {
        LambdaQueryWrapper<Template> wrapper = new LambdaQueryWrapper<Template>()
                .orderByAsc(Template::getName)
                .orderByAsc(Template::getVersion);
        if (name != null && !name.isBlank()) {
            wrapper.like(Template::getName, name.trim());
        }
        return templateMapper.selectList(wrapper);
    }

    private String resolveVersion(TemplateRequest request) {
        if (request.version() != null && !request.version().isBlank()) {
            return request.version().trim();
        }
        return nextVersion(request.name().trim());
    }

    private String nextVersion(String name) {
        List<Template> sameName = templateMapper.selectList(new LambdaQueryWrapper<Template>()
                .eq(Template::getName, name));
        int max = sameName.stream()
                .map(Template::getVersion)
                .filter(v -> v != null && v.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return String.valueOf(max + 1);
    }

    private void ensureNoConflict(String name, String version, Long excludeId) {
        LambdaQueryWrapper<Template> wrapper = new LambdaQueryWrapper<Template>()
                .eq(Template::getName, name)
                .eq(Template::getVersion, version);
        if (excludeId != null) {
            wrapper.ne(Template::getId, excludeId);
        }
        if (templateMapper.selectCount(wrapper) > 0) {
            throw BusinessException.of(BusinessCode.DUPLICATE_NAME, "模板已存在: " + name + " v" + version);
        }
    }

    private void validate(TemplateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (request.configJson() == null || request.configJson().isEmpty()) {
            throw new IllegalArgumentException("模板 configJson 不能为空");
        }
    }
}
