package com.sailheader.middleware.constant;

/**
 * 状态码
 */
public enum StateCode {
    SYSTEM_SUCCESS("SYSTEM_SUCCESS", "成功"),
    SYSTEM_FAIL( "SYSTEM_FAIL","失败"),
    SYSTEM_ERROR("SYSTEM_ERROR", "错误"),
    ADD_SUCCESS("COMMON.ADD_SUCCESS", "添加成功"),
    ADD_FAIL("COMMON.ADD_FAIL", "添加失败"),
    EDIT_SUCCESS("COMMON.EDIT_SUCCESS", "编辑成功"),
    EDIT_FAIL("COMMON.EDIT_FAIL", "编辑失败"),
    QUERY_SUCCESS("QUERY_SUCCESS", "查询成功"),
    DELETE_SUCCESS("COMMON.DELETE_SUCCESS", "删除成功"),
    DELETE_FAIL("COMMON.DELETE_FAIL", "删除失败");
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
