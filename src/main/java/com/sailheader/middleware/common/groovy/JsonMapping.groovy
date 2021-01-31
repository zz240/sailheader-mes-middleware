package com.sailheader.middleware.common.groovy

import groovy.json.JsonSlurper

def mappingJson(placeholderData, preContext) {
    preContext = new JsonSlurper().parseText(preContext)
    deepReplace(placeholderData, preContext)
    return preContext
}

static def deepReplace(context, list) {
    if (list instanceof Map) {
        list.each { index, value ->
            if (value instanceof List || value instanceof Map) {deepReplace(context, value)}
            else if (value instanceof String && value ==~ /^\$\S+/) {
                list[index] = actValToGet(context, value.substring(1))
            }
        }
    }else if (list instanceof List) {
        list.eachWithIndex { value, index ->
            if (value instanceof List || value instanceof Map) {deepReplace(context, value)}
            else if (value instanceof String && value ==~ /^\$\S+/) {
                list[index] = actValToGet(context, value.substring(1))
            }
        }
    }
    return list
}

static actValToGet(def resp, String params) {
    params.split(/\./).inject(resp) { map, key ->
        if (map instanceof List) key = Integer.parseInt(key)
        return map[key]
    }
}


