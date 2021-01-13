package com.sailheader.middleware.model;

import com.alibaba.fastjson.JSONObject;
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
public class Action implements Serializable {
    private static final long serialVersionUID = 1L;

    //id
    private Long id;

    //绑定任务id
    private Long jobId;

    //动作任务
    private String name;

    //动作类型
    private Short type;

    //状态（1:启用,2:未启用,-1:已删除';）
    private Short status;

    //动作序列
    private int index;

    //当前动作输出数据
    private JSONObject tempData;

    //附加辅助信息
    private JSONObject addition;

    //创建人id
    private Integer createdBy;

    //创建时间
    private Date createdOn;

    //更改人id
    private Integer modifiedBy;

    //更改时间
    private Date modifiedOn;
}
