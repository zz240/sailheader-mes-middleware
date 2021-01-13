package com.sailheader.middleware.constant;

public enum GeneralStatus {
    ACTIVE((short) 1, "启用"),
    DELETED((short) -1, "已删除"),
    DISABLE((short) 2, "未启用");

    private Short enUSLabel;
    private String zhCNLabel;

    public Short getIntLabel() {
        return enUSLabel;
    }
    public String getZhCNLabel() {
        return zhCNLabel;
    }
    public String getValue(){
        return name();
    }

    GeneralStatus(Short enUSLabel, String zhCNLabel){
        this.enUSLabel = enUSLabel;
        this.zhCNLabel = zhCNLabel;
    }


}
