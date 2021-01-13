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
 * 数据源表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSource implements Serializable {
    private static final long serialVersionUID = 1L;

    // id
    private Long id;

    //数据源名称
    private String name;

    //数据源类型
    private Integer type;

    //状态
    private Short status;

    //数据源地址
    private String address;

    //附加辅助信息
    private JSONObject addition;

    //创建人id
    private int createdBy;

    //创建时间
    private Date createdOn;

    //更改人id
    private int modifiedBy;

    //更改时间
    private Date modifiedOn;


}
