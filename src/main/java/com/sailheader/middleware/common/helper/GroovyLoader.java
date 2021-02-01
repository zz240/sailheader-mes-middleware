package com.sailheader.middleware.common.helper;

import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.MiddlewareExceptionMap;
import groovy.lang.Binding;
import groovy.lang.Script;
import groovy.util.GroovyScriptEngine;
import groovy.util.ResourceException;
import groovy.util.ScriptException;

import java.io.IOException;

public class GroovyLoader {
    public static Script loadGroovy(String groovyName) {

        GroovyScriptEngine engine = null;
        try {
            engine = new GroovyScriptEngine("src/main/java/com/sailheader/middleware/common/groovy");
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (engine == null) {
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.UNDEFINED_ERROR, "groovy 脚本初始化失败!");
        }

        Script script = null;
        try {
            script = engine.createScript(groovyName, new Binding());
        } catch (ResourceException | ScriptException e) {
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.UNDEFINED_ERROR, "groovy 脚本初始化失败!", e);
        }

        if (script == null) {
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.UNDEFINED_ERROR, "groovy 脚本初始化失败!");
        }

        return script;
    }
}
