package com.sailheader.middleware.common.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 操作结果
 */
@Data
@Accessors(chain = true)
public class OperationResult<T> {
    //数据
    private T data;
    //消息
    private String message;
    //状态码
    private String stateCode;


    public OperationResult(){

    }

    public OperationResult(T data){
        this.data = data;
    }

    public OperationResult(T data, String stateCode){
        this.data = data;
        this.stateCode = stateCode;
    }

    public OperationResult(T data, String stateCode, String message){
        this.data = data;
        this.stateCode = stateCode;
        this.message = message;
    }
}
