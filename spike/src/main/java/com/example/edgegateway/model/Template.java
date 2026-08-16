package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.util.Map;

/**
 * 模板：可复用的任务配置资产，按 name + version 唯一，支持导出/导入。
 */
@TableName(value = "template", autoResultMap = true)
public class Template {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String version;

    @TableField(value = "config_json", typeHandler = JacksonTypeHandler.class) // MySQL JSON 列
    private Map<String, Object> configJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, Object> getConfigJson() {
        return configJson;
    }

    public void setConfigJson(Map<String, Object> configJson) {
        this.configJson = configJson;
    }
}
