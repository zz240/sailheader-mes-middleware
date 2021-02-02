package com.sailheader.middleware.common.groovy

import com.alibaba.fastjson.JSON
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

class JsonMapping {
    static def mappingJson(placeholderData, JSON preContext) {
        placeholderData = new JsonSlurper().parseText(new JsonBuilder(placeholderData).toPrettyString())
        deepReplace(placeholderData, preContext)
        return preContext
    }

    static deepReplace(context, json) {
        if (json instanceof Map) {
            json.each { index, value ->
                if (value instanceof List || value instanceof Map) {
                    deepReplace(context, value)
                } else if (value instanceof String && value ==~ /^\$\S+/) {
                    json[index] = actValToGet(context, value.substring(1))
                }
            }
        } else if (json instanceof List) {
            json.eachWithIndex { value, index ->
                if (value instanceof List || value instanceof Map) {
                    deepReplace(context, value)
                } else if (value instanceof String && value ==~ /^\$\S+/) {
                    json[index] = actValToGet(context, value.substring(1))
                }
            }
        }
    }

    static actValToGet(def resp, String params) {
        params.split(/\./).inject(resp) { map, key ->
            if (map instanceof List) key = Integer.parseInt(key)
            return map[key]
        }
    }
}

