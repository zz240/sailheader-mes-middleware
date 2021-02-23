package com.sailheader.middleware.model;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.FastjsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 动作流表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "action_stream",autoResultMap = true)
public class Action implements Serializable {
    private static final long serialVersionUID = 1L;

    //id
    @TableId(value ="id", type = IdType.AUTO)
    private Long id;

    //绑定任务id
    private Long jobId;

    //动作名称
    private String name;

    //动作类型
    private Short type;

    //状态（1:启用,2:未启用,-1:已删除';）
    private Short status;

    //动作序列
    private int index;

    //附加辅助信息
    @TableField(typeHandler = FastjsonTypeHandler.class)
    private JSONObject addition;

    //创建人id
    private Integer createdBy;

    //创建时间
    private Date createdOn;

    //更改人id
    private Integer modifiedBy;

    //更改时间
    private Date modifiedOn;

    // 消费数据方式
    private int consumeDataType;
}
