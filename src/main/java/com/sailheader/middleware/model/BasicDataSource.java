package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 数据源配置表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BasicDataSource implements Serializable {
    /**
     * id
     */
    private Long id;

    /**
     * 编码
     */
    private String code;

    /**
     * 序号
     */
    private Long sequence;

    /**
     * 驱动名称
     */
    private String driverName;

    /**
     * 连接地址
     */
    private String url;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 密码
     */
    private String password;

    /**
     * 拼音
     */
    private String pinyin;

    /**
     * 英文名
     */
    private String enUsName;

    /**
     * 中文名
     */
    private String zhCnName;

    /**
     * 英文内容
     */
    private String enUsContent;

    /**
     * 中文内容
     */
    private String zhCnContent;

    /**
     * 状态
     */
    private String status;

    /**
     * orientDb中id
     */
    private String orientdbId;

    /**
     * 创建人id
     */
    private Long createdBy;

    /**
     * 创建人姓名
     */
    private String createdNameBy;

    /**
     * 创建时间
     */
    private Date createdDate;

    /**
     * 最后修改人id
     */
    private Long lastModifiedBy;

    /**
     * 最后修改人姓名
     */
    private String lastModifiedNameBy;

    /**
     * 最后修改时间
     */
    private Date lastModifiedDate;

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
        BasicDataSource other = (BasicDataSource) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
                && (this.getCode() == null ? other.getCode() == null : this.getCode().equals(other.getCode()))
                && (this.getSequence() == null ? other.getSequence() == null : this.getSequence().equals(other.getSequence()))
                && (this.getDriverName() == null ? other.getDriverName() == null : this.getDriverName().equals(other.getDriverName()))
                && (this.getUrl() == null ? other.getUrl() == null : this.getUrl().equals(other.getUrl()))
                && (this.getUserName() == null ? other.getUserName() == null : this.getUserName().equals(other.getUserName()))
                && (this.getPassword() == null ? other.getPassword() == null : this.getPassword().equals(other.getPassword()))
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
                && (this.getLastModifiedDate() == null ? other.getLastModifiedDate() == null : this.getLastModifiedDate().equals(other.getLastModifiedDate()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getCode() == null) ? 0 : getCode().hashCode());
        result = prime * result + ((getSequence() == null) ? 0 : getSequence().hashCode());
        result = prime * result + ((getDriverName() == null) ? 0 : getDriverName().hashCode());
        result = prime * result + ((getUrl() == null) ? 0 : getUrl().hashCode());
        result = prime * result + ((getUserName() == null) ? 0 : getUserName().hashCode());
        result = prime * result + ((getPassword() == null) ? 0 : getPassword().hashCode());
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
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", id=").append(id);
        sb.append(", code=").append(code);
        sb.append(", sequence=").append(sequence);
        sb.append(", driverName=").append(driverName);
        sb.append(", url=").append(url);
        sb.append(", userName=").append(userName);
        sb.append(", password=").append(password);
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
        sb.append(", serialVersionUID=").append(serialVersionUID);
        sb.append("]");
        return sb.toString();
    }
}
