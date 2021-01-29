package com.sailheader.middleware.common.groovy

import groovy.json.JsonSlurper
import groovy.transform.Field

def mapTest;

static def mappingJson(placeholderData, preContext) {
    def listTest = placeholderData["listTest"]
    def mapTest = placeholderData["mapTest"]
    def context = "{\"listTest\": [\"listTest[1]\"], \"mapTest\": \"mapTest['name']\"}"
    def result = new JsonSlurper().parseText(context)
    result.collect { k, v ->
        {
            println k
            if (v instanceof String) {
                def n = v;
                println(n)
                v = "??"
            }
            result[k] = v
        }
    }
    println result

//    Eval.me(result)
    return result
}

