package com.sailheader.middleware.constant;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 中间件异常错误码和异常说明
 * code 构成: 例如第一条 -> {项目当次主版本号(01)当前功能分支号(01)递增的序号(001)}
 */
public enum MiddlewareExceptionMap {
    ACTION_HANDLER_ADAPTER_ACTION_IS_NULL("0101001", "Action Data 为空!"),
    ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM("0101002", "Action Data 缺少必要的参数!"),
    JSON_MAPPING_PLACEHOLDER_CANT_PARSE("0101003", "无法解析表达式，请确保一个 #{#xx} 输出存在并且内容为基础类型，而非collection或者map!"),
    REMOTE_ACCESS_ACTION_DATASOURCE_IS_NULL("0101004", "远程访问动作 datasource 为空!"),
    REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM("0101005", "datasource 缺少必要的参数!"),
    REMOTE_ACCESS_ACTION_ACCESS_BEAN_NOT_FOUND("0101006", "为找到远程访问相关的bean!");

    @Getter
    private final String code;
    @Getter
    private final String msg;

    MiddlewareExceptionMap(String code, String msg){
        this.code = code;
        this.msg = msg;
    }
}
