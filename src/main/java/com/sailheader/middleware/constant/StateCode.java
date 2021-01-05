package com.sailheader.middleware.constant;

/**
 * 状态码
 */
public enum StateCode {
    SYSTEM_SUCCESS("SYSTEM_SUCCESS", "成功"),
    SYSTEM_FAIL( "SYSTEM_FAIL","失败"),
    SYSTEM_ERROR("SYSTEM_ERROR", "错误");
    private String enUSLabel;
    private String zhCNLabel;
    public String getEnUSLabel() {
        return enUSLabel;
    }
    public String getZhCNLabel() {
        return zhCNLabel;
    }
    public String getValue(){
        return name();
    }

    StateCode(String enUSLabel, String zhCNLabel){
        this.enUSLabel = enUSLabel;
        this.zhCNLabel = zhCNLabel;
    }
}
