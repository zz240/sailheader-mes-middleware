package com.sailheader.middleware.action.mapper;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 该动作有一些特殊
 * 跟单链上的预处理功能重复了，那么这个动作作为一个空动作存在
 * 如果后期需要从单链上抽出来那么另行说明
 * todo
 */

class FieldMappingWithObjectTests {

    FieldMappingWithObject fieldMappingWithObject;

    @BeforeEach
    public void init() {
        fieldMappingWithObject = new FieldMappingWithObject();
    }

    @Test
    public void testSuccess() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("name", "steven");
        fieldMappingWithObject.setAction(new Action().setAddition(jsonObject));

        ActionTempData<JSON> mapping = fieldMappingWithObject
                .mapping(new ActionTempData<>(ActionStatus.INITIAL, null));

        assertEquals(JSONObject.parseObject(mapping.getBody().toJSONString()).getString("name"), jsonObject.get("name"));
    }
}