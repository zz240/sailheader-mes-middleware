package com.sailheader.middleware.common.helper;

import java.text.ParseException;
import java.util.Date;

import org.quartz.CronExpression;
import org.quartz.impl.triggers.CronTriggerImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TaskUtils {
    /**
     * 校验定时任务格式
     */
    public static boolean isValidExpressionStr(String expressionStr){
        if(!StringUtils.hasText(expressionStr)){
            return false;
        }
        return CronExpression.isValidExpression(expressionStr);
    }

    /**
     * 校验表达式是否能触发(相对于当前时间)
     *
     * @throws ParseException
     */
    public static boolean isCanDoExpression(String expressionStr) throws ParseException{
        //先判断表达式格式是否正确
        if(!isValidExpressionStr(expressionStr)){
            return false;
        }
        CronTriggerImpl triggerImpl=new CronTriggerImpl();
        triggerImpl.setCronExpression(expressionStr);
        Date date=triggerImpl.computeFirstFireTime(null);
        return date!=null&&date.after(new Date());
    }

}
