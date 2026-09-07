package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.util.Map;

/**
 * 数据源：一个可配置的外部连接实例。type 决定连接器类型（HTTP/MySQL/RabbitMQ），
 * addition 保存连接级 JSON 配置对象（HTTP 的 token、MySQL 的 username/password、
 * RabbitMQ 的 host/port/username/password；具体资源 path/method/queue/exchange/routingKey 在动作里配，ADR-0007）。
 */
@TableName(value = "data_source", autoResultMap = true)
public class DataSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long siteId;

    private String name;

    private DataSourceType type; // 枚举按名字存 VARCHAR，例如 MYSQL

    private String address;

    @TableField(typeHandler = JacksonTypeHandler.class) // MySQL JSON 列，读写自动序列化/反序列化
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
