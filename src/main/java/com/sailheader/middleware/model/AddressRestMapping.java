package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * REST映射同步表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressRestMapping implements Serializable {
    private static final long serialVersionUID = 1L;

    //id
    private Long id;

    //源地址
    private String restUrl;

    //数据源
    private String restDataFleid;

    //中文名
    private String zhCnName;

    //CRON表达式
    private String cronExpression;

    //是否启用
    private Boolean enabled;

    //状态
    private String status;

    //目标地址
    private String targetAddress;

    //最后同步时间
    private Date lastSyncDate;

    //最后同步状态
    private String lastSyncStatus;


}
