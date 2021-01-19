package com.sailheader.middleware.chain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandler;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.action.RemoteAccessActionHandler;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SingleLinerJobActionHandlerChainTests {

    @Autowired
    ApplicationContext context;

    // 构造一个空参,模拟字段映射动作
    ActionHandlerAdapter<JSON, JSON> actionHandlerAdapter = new ActionHandlerAdapter<JSON, JSON>() {
        @Override
        protected ActionTempData<JSON> execute(ActionTempData<JSON> object) throws Exception {
            return object;
        }

        @Override
        protected void validateActionAddition() throws Exception {

        }
    };


    /**
     * 测试多个动作流程
     */
    @Test
    public void testMultipleActionChain() throws Exception {
        SingleLinerJobActionHandlerChain singleLinerJobActionHandlerChain = new SingleLinerJobActionHandlerChain(
                new ArrayList<>());
        // 1. 模拟一个数据库访问动作
        Action action1 = new Action().setJobId(1L).setName("test1").setType((short) 1).setIndex(1);
        JSONObject jsonObject1 = new JSONObject();
        DataSource dataSource1 = new DataSource();
        jsonObject1.put("sql", "select * from action_stream");
        jsonObject1.put("driver", "org.postgresql.Driver");
        jsonObject1.put("username", "middleware_user");
        jsonObject1.put("password", "860fbbdd75de0b5d517323d6f1832e10");

        dataSource1.setType(2);
        dataSource1.setName("远程访问");
        dataSource1.setAddress("jdbc:postgresql://localhost:9001/middleware");
        dataSource1.setAddition(jsonObject1);
        action1.setAddition(JSONObject.parseObject(JSONObject.toJSONString(dataSource1)));
        JSONObject jsonObject2 = new JSONObject();
        jsonObject2.put("sql", "INSERT INTO test VALUES (1, '#{#current[0]['name']}', 150);");
        action1.setTempData(jsonObject2);

        RemoteAccessActionHandler remoteAccessActionHandler = context.getBean("action.remote.access", RemoteAccessActionHandler.class);
        remoteAccessActionHandler.setAction(action1);
        // 加入动作链
        singleLinerJobActionHandlerChain.addAction(remoteAccessActionHandler);

        // ***************************************
        // 动作2 执行 sql 语句
        Action action2 = new Action().setJobId(1L).setName("test1").setType((short) 1).setIndex(1);
        JSONObject jsonObject3 = new JSONObject();
        DataSource dataSource2 = new DataSource();
        jsonObject3.put("driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        jsonObject3.put("username", "SA");
        jsonObject3.put("password", "Root123456");

        dataSource2.setType(2);
        dataSource2.setName("远程访问");
        dataSource2.setAddress("jdbc:sqlserver://localhost:9002;database=middleware");
        dataSource2.setAddition(jsonObject3);
        action2.setAddition(JSONObject.parseObject(JSONObject.toJSONString(dataSource2)));

        RemoteAccessActionHandler remoteAccessActionHandler2 = context.getBean("action.remote.access", RemoteAccessActionHandler.class);
        remoteAccessActionHandler2.setAction(action2);
        // 加入动作链
        singleLinerJobActionHandlerChain.addAction(remoteAccessActionHandler2);

        // 执行动作
        ActionTempData<?> actionTempData = singleLinerJobActionHandlerChain.dataHandler(null);


    }

}