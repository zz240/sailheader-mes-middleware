package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("watermark")
public class Watermark {

    @TableId("job_key")
    private String jobKey;

    @TableField("wm_value")
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
