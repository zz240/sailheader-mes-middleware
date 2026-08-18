package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.edgegateway.security.SensitiveAdditionTypeHandler;

import java.util.Map;

/**
 * 数据源：一个可配置的外部连接实例。type 决定连接器类型（HTTP/MySQL/RabbitMQ），
 * addition 保存类型相关的 JSON 配置对象（HTTP 的 method、MySQL 的 url/username/password、RabbitMQ 的 queue）。
 */
@TableName(value = "data_source", autoResultMap = true)
public class DataSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long siteId;

    private String name;

    private DataSourceType type; // 枚举按名字存 VARCHAR，例如 MYSQL

    private String address;

    @TableField(typeHandler = SensitiveAdditionTypeHandler.class) // 敏感字段加密存储，读写透明加解密
    private Map<String, Object> addition;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DataSourceType getType() {
        return type;
    }

    public void setType(DataSourceType type) {
        this.type = type;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Map<String, Object> getAddition() {
        return addition;
    }

    public void setAddition(Map<String, Object> addition) {
        this.addition = addition;
    }
}
