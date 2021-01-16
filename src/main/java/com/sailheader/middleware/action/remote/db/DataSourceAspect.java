package com.sailheader.middleware.action.remote.db;

import com.sailheader.middleware.model.DataSource;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 当执行一个访问外部数据库的方法时
 * 动态切换datasource配置，实现访问不同外部数据库
 */

@Aspect
@Component
public class DataSourceAspect {

    @Autowired
    private DruidUtil druidUtil;

    @Pointcut("execution(* com.sailheader.middleware.action.remote.db.service.*.*(..))")
    public void daoAspect() {
    }

    @Before("daoAspect()")
    public void beforeServiceMethodExecute(JoinPoint point) throws Exception {
        MethodSignature methodSignature = (MethodSignature) point.getSignature();
        Method targetMethod = methodSignature.getMethod();
        Parameter[] parameters = targetMethod.getParameters();
        Object[] args = point.getArgs();
        if (null == parameters || parameters.length == 0) {
            return;
        }
        DataSource dataSource = null;
        for (Object arg : args) {
            if (arg instanceof DataSource) {
                dataSource = (DataSource) arg;
            }
        }
        druidUtil.switchDB(dataSource);

    }

    /**
     * 方法执行完成,清除数据源
     *
     * @Param: point【接入点】
     * @return: void
     **/
    @After("daoAspect()")
    public void afterServiceMethodExecute(JoinPoint point) {
        DynamicDataSourceHandler.clear();
    }
}
