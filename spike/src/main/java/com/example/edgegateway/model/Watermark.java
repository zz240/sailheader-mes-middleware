package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 增量水位：记录某任务上次同步到的位置，成功执行后更新。
 */
@TableName("watermark")
public class Watermark {

    @TableId("job_key") // 主键约定为 "jobId:key" 字符串
    private String jobKey;

    @TableField("wm_value") // 对应列 wm_value，避免 value 关键字
    private Long wmValue;

    public String getJobKey() {
        return jobKey;
    }

    public void setJobKey(String jobKey) {
        this.jobKey = jobKey;
    }

    public Long getWmValue() {
        return wmValue;
    }

    public void setWmValue(Long wmValue) {
        this.wmValue = wmValue;
    }
}
