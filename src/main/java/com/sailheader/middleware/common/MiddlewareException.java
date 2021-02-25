package com.sailheader.middleware.common;

import com.sailheader.middleware.constant.MiddlewareExceptionMap;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 中间件自定义异常类
 */
@Slf4j
public class MiddlewareException extends RuntimeException {

    @Setter
    @Getter
    private MiddlewareExceptionMap exceptionMap;

    public MiddlewareException(MiddlewareExceptionMap map, String s, Throwable throwable) {
        super(s, throwable);
        this.exceptionMap = map;
    }

    public static MiddlewareException getInstance(MiddlewareExceptionMap map) {
        return getInstance(map, null, null);
    }

    public static MiddlewareException getInstance(MiddlewareExceptionMap map, String msg) {
        return getInstance(map, msg, null);
    }

    public static MiddlewareException getInstance(MiddlewareExceptionMap map, Exception e) {
        return getInstance(map, null, e);
    }

    public static MiddlewareException getInstance(MiddlewareExceptionMap map, String msg, Exception e) {
        if (msg == null || msg.equals("")) {
            msg = map.getMsg();
        } else {
            msg = map.getMsg() + "--" + msg;
        }

        if (e != null && e.getMessage() != null) {
            msg += "--" + e.getMessage();
        }
        log.error(msg);
        return new MiddlewareException(map, msg, e);
    }

}
