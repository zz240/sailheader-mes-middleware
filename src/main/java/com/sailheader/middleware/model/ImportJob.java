package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 定时任务表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportJob implements Serializable {
    private static final long serialVersionUID = 1L;

    //id
    private Long id;

    //源地址
    private String restUrl;

    //中文名
    private String zhCnName;

    //CRON表达式
    private String cronExpression;

    //目标地址
    private String targetAddress;

    //备注
    private String remarks;

    //是否启用
    private Boolean enabled;

    //状态
    private String status;

    //创建时间
    private Date createDate;

    //最后同步时间
    private Date lastSyncDate;

    //最后同步状态
    private String lastSyncStatus;







}
