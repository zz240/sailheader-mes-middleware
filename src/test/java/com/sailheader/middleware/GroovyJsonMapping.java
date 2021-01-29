package com.sailheader.middleware;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.common.helper.MappingJsonData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GroovyJsonMapping {
    @Test
    public void testPlaceholderMapping() throws Exception {
        ExpressionParser expressionParser = new SpelExpressionParser();
        EvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        List numbers = (List) expressionParser.parseExpression("{1,2,3,4}").getValue(ctx);
        Map mapOfMaps = (Map) expressionParser
                .parseExpression("{name:{first:'Nikola',last:'Tesla'},dob:{day:10,month:'July',year:1856}}")
                .getValue(ctx);


        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("mapTest", mapOfMaps);
        jsonMap.put("listTest", numbers);

        String context = "{'listTest': listTest[1], 'mapTest': mapTest['name']}";

        Object o = MappingJsonData.groovyJsonMapping(jsonMap, context);
        System.out.println(o);
    }
}
