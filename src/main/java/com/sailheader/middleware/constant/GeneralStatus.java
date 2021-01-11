package com.sailheader.middleware.constant;

public enum GeneralStatus {
    ACTIVE("Active", "正常"),
    DELETED("Deleted", "已删除"),
    NULL("NULL", "是空");

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
    GeneralStatus(String enUSLabel, String zhCNLabel){
        this.enUSLabel = enUSLabel;
        this.zhCNLabel = zhCNLabel;
    }

}
