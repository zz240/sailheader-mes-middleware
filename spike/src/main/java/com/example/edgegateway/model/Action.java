package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 动作：任务动作链中的最小执行单元。按 index 升序执行；
 * addition 保存动作类型相关的 JSON 配置（如远程访问的地址/SQL、字段映射规则）。
 */
@TableName("job_action")
public class Action {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String name;

    private ActionType type; // 动作类型：远程访问 / 字段映射，按名字存 VARCHAR

    @TableField("action_index") // 字段名 index 与列名 action_index 不对应，显式映射
    private Integer actionIndex;

    private String addition; // JSON 配置串，由动作执行逻辑解析

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

    public ActionType getType() {
        return type;
    }

    public void setType(ActionType type) {
        this.type = type;
    }

    public Integer getActionIndex() {
        return actionIndex;
    }

    public void setActionIndex(Integer actionIndex) {
        this.actionIndex = actionIndex;
    }

    public String getAddition() {
        return addition;
    }

    public void setAddition(String addition) {
        this.addition = addition;
    }
}
