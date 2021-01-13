package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 执行任务表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job implements Serializable {
    private static final long serialVersionUID = 1L;

    //id
    private Long id;

    //任务名称
    private String name;

    //定时表达式
    private String crontab;

    //备注
    private String remarks;

    //状态  1:启用,2:未启用,-1:已删除';
    private Short status;

    //创建人id
    private int createdBy;

    //创建时间
    private Date createdOn;

    //更改人id
    private int modifiedBy;

    //更新时间
    private Date modifiedOn;




}
