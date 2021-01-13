package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 执行任务日志表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobLog implements Serializable {
    private static final long serialVersionUID = 1L;

    //id
    private Long id;

    //执行任务id
    private Long JobId;

    //操作数（1：insert ；2：update ；3：delete）
    private Short operation;

    //更改内容
    private String jobContent;

    //更改人
    private int modifiedBy;

    //更改时间
    private Date modifiedOn;


}
