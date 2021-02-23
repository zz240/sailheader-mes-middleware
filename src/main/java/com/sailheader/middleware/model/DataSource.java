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
 * 数据源表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(autoResultMap = true)
public class DataSource implements Serializable {
    private static final long serialVersionUID = 1L;

    // id
    @TableId(type = IdType.AUTO)
    private Long id;

    //数据源名称
    private String name;

    //数据源类型
    private Integer type;

    //状态   1:启用,2:未启用,-1:已删除';
    private Short status;

    //数据源地址
    private String address;

    //附加辅助信息
    @TableField(typeHandler = FastjsonTypeHandler.class)
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
