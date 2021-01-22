package com.sailheader.middleware.common.helper;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.MiddlewareExceptionMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;

@Component
public class MappingJsonData {

    private static ExpressionParser expressionParser;
    private static TemplateParserContext templateParserContext;

    @PostConstruct
    public void getExpressionParse() {
        expressionParser = new SpelExpressionParser();
        templateParserContext = new TemplateParserContext();
    }

    /**
     * 根据输入JSON数据替换JSON字符串中的占位符
     *
     * @param placeholderData 替换占位符的数据
     * @param preContext      待替换的上下文
     * @return
     */
    public static JSON mappingJsonIntoJson(Map<String, Object> placeholderData, JSON preContext) throws Exception {

        if (preContext == null) {
            return null;
        }

        if (placeholderData == null) {
            return preContext;
        }

        // 1. 使用 SpEL 将准备好的 lastInput 和 current 注入上下文
        EvaluationContext ctx = new StandardEvaluationContext();
        placeholderData.forEach(ctx::setVariable);

        // 2. 将当前上下文和 current 下的表达式内容合并
        JSON object;
        try {
            String resultStr = expressionParser
                    .parseExpression(JSON.toJSONString(preContext), templateParserContext)
                    .getValue(ctx, String.class);
            object = (JSON) JSON.parse(resultStr);
        } catch (Exception e) {
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.JSON_MAPPING_PLACEHOLDER_CANT_PARSE);
        }

        return object;
    }

}
