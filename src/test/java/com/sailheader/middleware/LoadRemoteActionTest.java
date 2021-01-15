package com.sailheader.middleware;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.action.RemoteAccessActionHandler;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.DataSourceType;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class LoadRemoteActionTest {

    @Autowired
    RemoteAccessActionHandler remoteAccessActionHandler;

    /**
     * 测试访问远程动作
     * @throws Exception
     */
    @Test
    public void getRemoteAccessOfHttp() throws Exception {

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("method", "get");

        // 加载DataSource
        DataSource dataSource = new DataSource();
        dataSource.setType(DataSourceType.HTTP.getId());
        dataSource.setAddition(jsonObject);

        // 加载Action
        Action action = new Action();
        action.setAddition((JSONObject) JSON.toJSON(dataSource));

        remoteAccessActionHandler.setAction(action);

        ActionTempData<JSON> tempData = new ActionTempData<>(ActionStatus.INITIAL, null);
        System.out.println(remoteAccessActionHandler.dataHandler(tempData));
    }

    /**
     * 测试db访问远程动作
     * @throws Exception
     */
    @Test
    public void getRemoteAccessOfDb() throws Exception {

        // 构造数据源 addition
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("sql", "select * from action_stream");
        jsonObject.put("driver", "org.postgresql.Driver");
        jsonObject.put("userName", "middleware_user");
        jsonObject.put("password", "860fbbdd75de0b5d517323d6f1832e10");


        // 加载DataSource
        DataSource dataSource = new DataSource();
        dataSource.setType(DataSourceType.DB.getId());
        dataSource.setAddition(jsonObject);
        dataSource.setAddress("jdbc:postgresql://192.168.1.115:9001/middleware");

        // 加载Action
        Action action = new Action();
        action.setAddition((JSONObject) JSON.toJSON(dataSource));

        remoteAccessActionHandler.setAction(action);

        ActionTempData<JSON> tempData = new ActionTempData<>(ActionStatus.INITIAL, null);
        ActionTempData<JSON> result = remoteAccessActionHandler.dataHandler(tempData);
        System.out.println(result.getBody());
    }

}
