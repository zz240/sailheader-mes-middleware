package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;


/**
 * 数据源日志表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceLog implements Serializable {
    private static final long serialVersionUID = 1L;
    //id
    private Long id;

    //数据源id
    private Long dataSourceId;

    //操作数（1：insert；2：update；3：delete）
    private Short operation;

    //更改内容
    private String content;

    //更改人
    private int modifiedBy;

    //更改时间
    private Date modifiedOn;
}
