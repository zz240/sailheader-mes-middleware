package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.model.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RemoteAccessActionHandlerTests {

    @Autowired
    RemoteAccessActionHandler remoteAccessActionHandler;

    /**
     * 检查 datasource 有效性
     */
    @Nested
    @DisplayName("check action addition datasource effectiveness")
    class CheckDataSource {

        Action action = new Action();

        @Test
        public void testDataSourceNonName() {
            action.setAddition(new JSONObject());
            remoteAccessActionHandler.setAction(action);
//            remoteAccessActionHandler.execute()
        }


    }
}