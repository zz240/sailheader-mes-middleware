package com.sailheader.middleware;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.helper.MappingJsonData;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

        JSON context = (JSON) JSON.parse("{\"listTest\": [\"$listTest.1\"], \"mapTest\": \"$mapTest.name\"}");

        JSONObject o = (JSONObject) MappingJsonData.groovyJsonMapping(jsonMap, context);

        assertEquals(o.getJSONArray("listTest").get(0), 2);
        assertEquals(o.getJSONObject("mapTest").getString("first"), "Nikola");
    }
}
