package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.util.Map;

/**
 * 动作：任务动作链中的最小执行单元。按 index 升序执行；
 * addition 保存动作类型相关的 JSON 配置对象（如远程访问的 dataSourceId/SQL、字段映射规则）。
 */
@TableName(value = "job_action", autoResultMap = true)
public class Action {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String name;

    private ActionKind type; // 动作种类（DS-8）：MQ_CONSUME/READ_SQL/FIELD_MAP/WRITE_UPSERT/HTTP_CALL/MQ_PUBLISH

    @TableField("action_index") // 字段名 index 与列名 action_index 不对应，显式映射
    private Integer actionIndex;

    @TableField(typeHandler = JacksonTypeHandler.class) // MySQL JSON 列
    private Map<String, Object> addition;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ActionKind getType() {
        return type;
    }

    public void setType(ActionKind type) {
        this.type = type;
    }

    public Integer getActionIndex() {
        return actionIndex;
    }

    public void setActionIndex(Integer actionIndex) {
        this.actionIndex = actionIndex;
    }

    public Map<String, Object> getAddition() {
        return addition;
    }

    public void setAddition(Map<String, Object> addition) {
        this.addition = addition;
    }
}
