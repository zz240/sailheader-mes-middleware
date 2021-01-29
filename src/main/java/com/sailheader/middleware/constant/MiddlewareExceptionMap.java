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
    ACTION_HANDLER_ADAPTER_ACTION_SUITABLE_TYPE_NOT_FOUND("0101003", "Action 未找到合适的类型"),
    ACTION_HANDLER_ADAPTER_ACTION_IMPL_CLASS_NOT_FOUND("0101004", "Action 未能找到相关的实现类"),
    ACTION_HANDLER_ADAPTER_ACTION_EXECUTE_FAILURE("0101005", "未能成功执行 Action!"),
    JSON_MAPPING_PLACEHOLDER_CANT_PARSE("0101003", "无法解析表达式，请确保一个 #{#xx} 输出存在并且内容为基础类型，而非collection或者map!"),
    REMOTE_ACCESS_ACTION_DATASOURCE_IS_NULL("0101004", "远程访问动作 datasource 为空!"),
    REMOTE_ACCESS_ACTION_DATASOURCE_PARSE_ERROR("0101005", "datasource 字段解析错误!"),
    REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM("0101006", "datasource 缺少必要的参数!"),
    REMOTE_ACCESS_ACTION_ACCESS_BEAN_NOT_FOUND("0101007", "未找到远程访问相关的 bean!"),
    REMOTE_ACCESS_ACTION_ACCESS_BEAN_INIT_FAILURE("0101008", "未能成功初始化远程访问的 bean"),
    REMOTE_ACCESS_DB_DATASOURCE_IS_NULL("0101009", "远程访问数据库 datasource 为空!"),
    REMOTE_ACCESS_DB_DATASOURCE_ADDITION_IS_NULL("0101010", "远程访问数据库 datasource addition 为空!"),
    REMOTE_ACCESS_DB_DATASOURCE_MISSING_REQUIRE_PARAM("0101011", "远程访问数据库 datasource 缺少必要的参数!"),
    ACTION_STATUS_CONFUSE_ACCESS("0101012", "输入数据状态异常,拒绝访问!"),
    REMOTE_ACCESS_DB_SWATCH_FAILURE("0101013", "切换不同数据库失败!"),
    REMOTE_ACCESS_DB_RESULT_CANT_CONVERT_JSON("0101014", "无法解析查询结果转化为json!"),
    REMOTE_ACCESS_DB_SQL_IS_NULL("0101015", "远程访问数据库 待执行的 sql 为空!"),
    REMOTE_ACCESS_DB_CONNECT_FAILURE("0101016", "远程访问数据库 访问目的数据库失败!"),
    REMOTE_ACCESS_DB_SQL_EXECUTE_FAILURE("0101017", "远程访问数据库 执行sql语句失败，请确认sql语句正确性!"),
    JOB_ACTION_CHAIN_EXECUTE_INTERRUPT("0101018", "动作链执行中断!"),
    JOB_ACTION_CHAIN_CONTEXT_DATA_COPY_ERROR("0101019", "动作链中注入上下文的数据 复制失败"),
    ACTION_HANDLER_ADAPTER_ACTION_CONSUME_IMPL_NOT_FOUND("0101020", "Action 未找到消费数据的类型"),

    REMOTE_ACCESS_HTTP_MISSING_REQUIRE_PARAM("0102001","缺少必要参数"),
    REMOTE_ACCESS_HTTP_DATASOURCE_IS_NULL("0102002","远程访问 datasource 为空"),
    REMOTE_ACCESS_HTTP_ACCESS_ERROR("0102003","http 远程访问异常"),
    REMOTE_ACCESS_HTTP_DATASOURCE_ADDITION_IS_NULL("0102004"," http 远程访问 addition 为空"),
    REMOTE_ACCESS_HTTP_DATASOURCE_ADDRESS_IS_NULL("0102005"," http 远程访问 address 为空"),
    REMOTE_ACCESS_HTTP_BUILD_FAILURE("0102006","构建 http 访问对象失败"),

    FIELD_MAPPING_CALSS_CAST_ERROR("0103001","输入数据类型转换异常 "),
    FIELD_MAPPING_ADDITION_IS_NULL("0103002","未能获取字段映射规则 "),
    FIELD_MAPPING_DATA_IS_NULL("0103003","字段映射的数据为空  "),


    UNDEFINED_ERROR("0000001", "未知错误!");

    @Getter
    private final String code;
    @Getter
    private final String msg;

    MiddlewareExceptionMap(String code, String msg){
        this.code = code;
        this.msg = msg;
    }
}
