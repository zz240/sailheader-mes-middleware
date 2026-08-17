package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 站点：每个客户现场一套部署，携带默认时区与 API Key（预留字段）。
 */
@TableName("site")
public class Site {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String timezone;

    private String apiKey;

    /** 映射建议器覆盖：rule | llm；为空时使用全局 edge.mapping.suggester。 */
    private String suggester;

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

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSuggester() {
        return suggester;
    }

    public void setSuggester(String suggester) {
        this.suggester = suggester;
    }
}
