package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.consume.DirectConsume;
import com.sailheader.middleware.action.mapper.FieldMappingWithArray;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class MappingHandlerAdapterTests {

    @Autowired
    FieldMappingWithArray fieldMappingWithArray;

    Action action = new Action().setJobId((long)32).setName("test").setType((short) 3).setIndex(1);


    /**
     * 检查字段映射规则有效性
     */
    @Nested
    @DisplayName("Check the validity of the field mapping")
    class CheckFieldMappingWithArray{
        JSONObject jsonObject;
        JSONObject addition;
        JSONObject object;
        JSONArray arr;
        @BeforeEach
        public void initJsonObject() {
            jsonObject = new JSONObject();
            jsonObject.put("name", "远程访问");
            jsonObject.put("type", 1);
            jsonObject.put("address", "https://");
            jsonObject.put("addition", new JSONObject());
            fieldMappingWithArray.setConsumeData(new DirectConsume());

            addition = new JSONObject();
            addition.put("tid","id");
            addition.put("miaoshu","desc");
            addition.put("tname","name");
        }

        @BeforeEach
        public void initFieldMapping(){
            arr = JSONArray.parseArray("[{\"miaoshu\":\"ball\"},{\"tid\":\"3\",\"tname\":\"steam\",\"desc\":\"game\"},{\"flag\":true,\"tname\":1611909871426,\"miaoshu\":\"test\",\"tid\":3}]");
            System.out.println("处理前的数据" + arr);
        }

        @Test
        public void testNormal() {
            JSONArray json = JSONArray.parseArray("[{\"desc\":\"ball\"},{\"name\":\"steam\",\"id\":\"3\",\"desc\":\"game\"},{\"flag\":true,\"name\":1611909871426,\"id\":3,\"desc\":\"test\"}]");
            action.setAddition(addition);
            fieldMappingWithArray.setAction(action);
            try {
                assertEquals(json,fieldMappingWithArray.dataHandler(new ActionTempData(ActionStatus.INITIAL,arr)).getBody());

            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        /**
         * 映射规则为空
         */
        @Test
        public void testadditionIsNull() {
            action.setAddition(null);
            fieldMappingWithArray.setAction(action);
            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> fieldMappingWithArray.dataHandler(new ActionTempData(ActionStatus.INITIAL,arr)));
            assertEquals(middlewareException.getExceptionMap(),
                    REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        /**
         *  需要映射的数据（入参） 为空
         */
        @Test
        public void testRawDataIsNull() {
            action.setAddition(jsonObject);
            fieldMappingWithArray.setAction(action);
            arr = null;

            MiddlewareException middlewareException = assertThrows(MiddlewareException.class,
                    () -> fieldMappingWithArray.dataHandler(new ActionTempData(ActionStatus.INITIAL,arr)));
            assertEquals(middlewareException.getExceptionMap(),
                    FIELD_MAPPING_DATA_IS_NULL);
        }

    }


}
