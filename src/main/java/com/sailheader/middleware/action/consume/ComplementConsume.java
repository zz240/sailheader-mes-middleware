package com.sailheader.middleware.action.consume;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.common.helper.MappingJsonData;
import com.sailheader.middleware.model.Action;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 通过消费数据补全自身
 * 需要在 addition 中设置占位符
 */
@Component("action.consume.completion")
public class ComplementConsume implements ConsumeData{
    @Override
    public void consume(ActionHandlerAdapter<?, ?> actionHandlerAdapter, Object inputData) throws Exception {
        // 替换 addition 占位符
        Action action = actionHandlerAdapter.getAction();
        Map<String, Object> map = new HashMap<>();
        map.put("lastData", inputData);
        JSONObject json = (JSONObject) MappingJsonData.mappingJsonIntoJson(map, action.getAddition());
        action.setAddition(json);

        // 消费注入数据
        actionHandlerAdapter.getSendData().setBody(null);
    }
}
