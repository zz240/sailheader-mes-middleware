package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 同步映射配置表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassConfig implements Serializable {
    //id
    private Long id;

    //类名
    private String className;

    //编码
    private String code;

    //SOAP地址
    private String soapWsdlUrl;

    //SOAP命名空间
    private String soapNameSpace;

    //SOAP服务名称
    private String soapServiceName;

    //SOAP响应名称
    private String soapResponseName;

    //SOAP参数
    private String soapParameterJson;

    //SOAP最后同步日期
    private String soapParameterLastSyncDateTime;

    //REST地址
    private String restUrl;

    //REST数据源
    private String restDataField;

    //类型
    private String type;

    //CRON表达式
    private String cronExpression;

    //序号
    private Long sequence;

    //最后同步开始时间
    private Date lastSyncStartDate;

    //最后同步结束时间
    private Date lastSyncEndDate;

    //最后同步状态
    private String lastSyncStatus;

    //是否执行删除
    private Boolean isExecuteDelete;

    //初始化SQL
    private String initSqlString;

    //增量SQL
    private String deltaSqlString;

    //主键SQL
    private String primaryKeySqlString;

    //倒推时长
    private Integer backMinutes;

    //是否启用
    private Boolean enabled;

    //拼音
    private String pinyin;

    //英文名
    private String enUsName;

    //中文名
    private String zhCnName;

    //英文内容
    private String enUsContent;

    //中文内容
    private String zhCnContent;

    //状态
    private String status;

    //orientDb中id
    private String orientdbId;

    //创建人id
    private Long createdBy;

    //创建人姓名
    private String createdNameBy;

    //创建时间
    private Date createdDate;

    //最后修改人id
    private Long lastModifiedBy;

    //最后修改人姓名
    private String lastModifiedNameBy;

    //最后修改时间
    private Date lastModifiedDate;

    //数据源id
    private Long basicDataSourceId;

    //回写数据源id
    private Long writeInBasicDataSourceId;

    private static final long serialVersionUID = 1L;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        ClassConfig other = (ClassConfig) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
                && (this.getClassName() == null ? other.getClassName() == null : this.getClassName().equals(other.getClassName()))
                && (this.getCode() == null ? other.getCode() == null : this.getCode().equals(other.getCode()))
                && (this.getSoapWsdlUrl() == null ? other.getSoapWsdlUrl() == null : this.getSoapWsdlUrl().equals(other.getSoapWsdlUrl()))
                && (this.getSoapNameSpace() == null ? other.getSoapNameSpace() == null : this.getSoapNameSpace().equals(other.getSoapNameSpace()))
                && (this.getSoapServiceName() == null ? other.getSoapServiceName() == null : this.getSoapServiceName().equals(other.getSoapServiceName()))
                && (this.getSoapResponseName() == null ? other.getSoapResponseName() == null : this.getSoapResponseName().equals(other.getSoapResponseName()))
                && (this.getSoapParameterJson() == null ? other.getSoapParameterJson() == null : this.getSoapParameterJson().equals(other.getSoapParameterJson()))
                && (this.getSoapParameterLastSyncDateTime() == null ? other.getSoapParameterLastSyncDateTime() == null : this.getSoapParameterLastSyncDateTime().equals(other.getSoapParameterLastSyncDateTime()))
                && (this.getRestUrl() == null ? other.getRestUrl() == null : this.getRestUrl().equals(other.getRestUrl()))
                && (this.getRestDataField() == null ? other.getRestDataField() == null : this.getRestDataField().equals(other.getRestDataField()))
                && (this.getType() == null ? other.getType() == null : this.getType().equals(other.getType()))
                && (this.getCronExpression() == null ? other.getCronExpression() == null : this.getCronExpression().equals(other.getCronExpression()))
                && (this.getSequence() == null ? other.getSequence() == null : this.getSequence().equals(other.getSequence()))
                && (this.getLastSyncStartDate() == null ? other.getLastSyncStartDate() == null : this.getLastSyncStartDate().equals(other.getLastSyncStartDate()))
                && (this.getLastSyncEndDate() == null ? other.getLastSyncEndDate() == null : this.getLastSyncEndDate().equals(other.getLastSyncEndDate()))
                && (this.getLastSyncStatus() == null ? other.getLastSyncStatus() == null : this.getLastSyncStatus().equals(other.getLastSyncStatus()))
                && (this.getIsExecuteDelete() == null ? other.getIsExecuteDelete() == null : this.getIsExecuteDelete().equals(other.getIsExecuteDelete()))
                && (this.getInitSqlString() == null ? other.getInitSqlString() == null : this.getInitSqlString().equals(other.getInitSqlString()))
                && (this.getDeltaSqlString() == null ? other.getDeltaSqlString() == null : this.getDeltaSqlString().equals(other.getDeltaSqlString()))
                && (this.getPrimaryKeySqlString() == null ? other.getPrimaryKeySqlString() == null : this.getPrimaryKeySqlString().equals(other.getPrimaryKeySqlString()))
                && (this.getBackMinutes() == null ? other.getBackMinutes() == null : this.getBackMinutes().equals(other.getBackMinutes()))
                && (this.getEnabled() == null ? other.getEnabled() == null : this.getEnabled().equals(other.getEnabled()))
                && (this.getPinyin() == null ? other.getPinyin() == null : this.getPinyin().equals(other.getPinyin()))
                && (this.getEnUsName() == null ? other.getEnUsName() == null : this.getEnUsName().equals(other.getEnUsName()))
                && (this.getZhCnName() == null ? other.getZhCnName() == null : this.getZhCnName().equals(other.getZhCnName()))
                && (this.getEnUsContent() == null ? other.getEnUsContent() == null : this.getEnUsContent().equals(other.getEnUsContent()))
                && (this.getZhCnContent() == null ? other.getZhCnContent() == null : this.getZhCnContent().equals(other.getZhCnContent()))
                && (this.getStatus() == null ? other.getStatus() == null : this.getStatus().equals(other.getStatus()))
                && (this.getOrientdbId() == null ? other.getOrientdbId() == null : this.getOrientdbId().equals(other.getOrientdbId()))
                && (this.getCreatedBy() == null ? other.getCreatedBy() == null : this.getCreatedBy().equals(other.getCreatedBy()))
                && (this.getCreatedNameBy() == null ? other.getCreatedNameBy() == null : this.getCreatedNameBy().equals(other.getCreatedNameBy()))
                && (this.getCreatedDate() == null ? other.getCreatedDate() == null : this.getCreatedDate().equals(other.getCreatedDate()))
                && (this.getLastModifiedBy() == null ? other.getLastModifiedBy() == null : this.getLastModifiedBy().equals(other.getLastModifiedBy()))
                && (this.getLastModifiedNameBy() == null ? other.getLastModifiedNameBy() == null : this.getLastModifiedNameBy().equals(other.getLastModifiedNameBy()))
                && (this.getLastModifiedDate() == null ? other.getLastModifiedDate() == null : this.getLastModifiedDate().equals(other.getLastModifiedDate()))
                && (this.getBasicDataSourceId() == null ? other.getBasicDataSourceId() == null : this.getBasicDataSourceId().equals(other.getBasicDataSourceId()))
                && (this.getWriteInBasicDataSourceId() == null ? other.getWriteInBasicDataSourceId() == null : this.getWriteInBasicDataSourceId().equals(other.getWriteInBasicDataSourceId()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getClassName() == null) ? 0 : getClassName().hashCode());
        result = prime * result + ((getCode() == null) ? 0 : getCode().hashCode());
        result = prime * result + ((getSoapWsdlUrl() == null) ? 0 : getSoapWsdlUrl().hashCode());
        result = prime * result + ((getSoapNameSpace() == null) ? 0 : getSoapNameSpace().hashCode());
        result = prime * result + ((getSoapServiceName() == null) ? 0 : getSoapServiceName().hashCode());
        result = prime * result + ((getSoapResponseName() == null) ? 0 : getSoapResponseName().hashCode());
        result = prime * result + ((getSoapParameterJson() == null) ? 0 : getSoapParameterJson().hashCode());
        result = prime * result + ((getSoapParameterLastSyncDateTime() == null) ? 0 : getSoapParameterLastSyncDateTime().hashCode());
        result = prime * result + ((getRestUrl() == null) ? 0 : getRestUrl().hashCode());
        result = prime * result + ((getRestDataField() == null) ? 0 : getRestDataField().hashCode());
        result = prime * result + ((getType() == null) ? 0 : getType().hashCode());
        result = prime * result + ((getCronExpression() == null) ? 0 : getCronExpression().hashCode());
        result = prime * result + ((getSequence() == null) ? 0 : getSequence().hashCode());
        result = prime * result + ((getLastSyncStartDate() == null) ? 0 : getLastSyncStartDate().hashCode());
        result = prime * result + ((getLastSyncEndDate() == null) ? 0 : getLastSyncEndDate().hashCode());
        result = prime * result + ((getLastSyncStatus() == null) ? 0 : getLastSyncStatus().hashCode());
        result = prime * result + ((getIsExecuteDelete() == null) ? 0 : getIsExecuteDelete().hashCode());
        result = prime * result + ((getInitSqlString() == null) ? 0 : getInitSqlString().hashCode());
        result = prime * result + ((getDeltaSqlString() == null) ? 0 : getDeltaSqlString().hashCode());
        result = prime * result + ((getPrimaryKeySqlString() == null) ? 0 : getPrimaryKeySqlString().hashCode());
        result = prime * result + ((getBackMinutes() == null) ? 0 : getBackMinutes().hashCode());
        result = prime * result + ((getEnabled() == null) ? 0 : getEnabled().hashCode());
        result = prime * result + ((getPinyin() == null) ? 0 : getPinyin().hashCode());
        result = prime * result + ((getEnUsName() == null) ? 0 : getEnUsName().hashCode());
        result = prime * result + ((getZhCnName() == null) ? 0 : getZhCnName().hashCode());
        result = prime * result + ((getEnUsContent() == null) ? 0 : getEnUsContent().hashCode());
        result = prime * result + ((getZhCnContent() == null) ? 0 : getZhCnContent().hashCode());
        result = prime * result + ((getStatus() == null) ? 0 : getStatus().hashCode());
        result = prime * result + ((getOrientdbId() == null) ? 0 : getOrientdbId().hashCode());
        result = prime * result + ((getCreatedBy() == null) ? 0 : getCreatedBy().hashCode());
        result = prime * result + ((getCreatedNameBy() == null) ? 0 : getCreatedNameBy().hashCode());
        result = prime * result + ((getCreatedDate() == null) ? 0 : getCreatedDate().hashCode());
        result = prime * result + ((getLastModifiedBy() == null) ? 0 : getLastModifiedBy().hashCode());
        result = prime * result + ((getLastModifiedNameBy() == null) ? 0 : getLastModifiedNameBy().hashCode());
        result = prime * result + ((getLastModifiedDate() == null) ? 0 : getLastModifiedDate().hashCode());
        result = prime * result + ((getBasicDataSourceId() == null) ? 0 : getBasicDataSourceId().hashCode());
        result = prime * result + ((getWriteInBasicDataSourceId() == null) ? 0 : getWriteInBasicDataSourceId().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", id=").append(id);
        sb.append(", className=").append(className);
        sb.append(", code=").append(code);
        sb.append(", soapWsdlUrl=").append(soapWsdlUrl);
        sb.append(", soapNameSpace=").append(soapNameSpace);
        sb.append(", soapServiceName=").append(soapServiceName);
        sb.append(", soapResponseName=").append(soapResponseName);
        sb.append(", soapParameterJson=").append(soapParameterJson);
        sb.append(", soapParameterLastSyncDateTime=").append(soapParameterLastSyncDateTime);
        sb.append(", restUrl=").append(restUrl);
        sb.append(", restDataField=").append(restDataField);
        sb.append(", type=").append(type);
        sb.append(", cronExpression=").append(cronExpression);
        sb.append(", sequence=").append(sequence);
        sb.append(", lastSyncStartDate=").append(lastSyncStartDate);
        sb.append(", lastSyncEndDate=").append(lastSyncEndDate);
        sb.append(", lastSyncStatus=").append(lastSyncStatus);
        sb.append(", isExecuteDelete=").append(isExecuteDelete);
        sb.append(", initSqlString=").append(initSqlString);
        sb.append(", deltaSqlString=").append(deltaSqlString);
        sb.append(", primaryKeySqlString=").append(primaryKeySqlString);
        sb.append(", backMinutes=").append(backMinutes);
        sb.append(", enabled=").append(enabled);
        sb.append(", pinyin=").append(pinyin);
        sb.append(", enUsName=").append(enUsName);
        sb.append(", zhCnName=").append(zhCnName);
        sb.append(", enUsContent=").append(enUsContent);
        sb.append(", zhCnContent=").append(zhCnContent);
        sb.append(", status=").append(status);
        sb.append(", orientdbId=").append(orientdbId);
        sb.append(", createdBy=").append(createdBy);
        sb.append(", createdNameBy=").append(createdNameBy);
        sb.append(", createdDate=").append(createdDate);
        sb.append(", lastModifiedBy=").append(lastModifiedBy);
        sb.append(", lastModifiedNameBy=").append(lastModifiedNameBy);
        sb.append(", lastModifiedDate=").append(lastModifiedDate);
        sb.append(", basicDataSourceId=").append(basicDataSourceId);
        sb.append(", writeInBasicDataSourceId=").append(writeInBasicDataSourceId);
        sb.append(", serialVersionUID=").append(serialVersionUID);
        sb.append("]");
        return sb.toString();
    }
}
