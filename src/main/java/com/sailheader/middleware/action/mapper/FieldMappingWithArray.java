package com.sailheader.middleware.action.mapper;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.FieldMappingActionHandler;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

@Slf4j
@Component("action.field.mapping.array")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FieldMappingWithArray extends FieldMappingActionHandler<JSONArray, JSONArray> {
    @Override

    protected ActionTempData<JSONArray> mapping(ActionTempData<JSONArray> actionTempData) {
        JSONObject keyMap = action.getAddition();
        JSONArray resArr = new JSONArray();

        if (actionTempData == null) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "输入数据为空");
        }
        if (actionTempData.getStatus() != ActionStatus.SUCCESS && actionTempData.getStatus() != ActionStatus.INITIAL) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "上一个动作需要成功 或者 动作初始化才能继续执行下一个动作!");
        }
        if (actionTempData.getBody() == null) {
            throw MiddlewareException.getInstance(FIELD_MAPPING_DATA_IS_NULL);
        }
        if (keyMap == null || keyMap.entrySet().size() == 0) {
            throw MiddlewareException.getInstance(FIELD_MAPPING_ADDITION_IS_NULL);
        }

        JSONArray array;
        try {
            // 获取并解析 原数据
            array = JSONArray.parseArray(JSON.toJSONString(actionTempData.getBody()));
        } catch (Exception e) {
            throw MiddlewareException.getInstance(FIELD_MAPPING_CALSS_CAST_ERROR, e);
        }

        JSONObject rawJson;
        // 根据 字段规则 处理 原数据
        for (int i = 0; i < array.size(); i++) {
            try {
                rawJson = array.getJSONObject(i);
            } catch (Exception e) {
                throw MiddlewareException.getInstance(FIELD_MAPPING_CALSS_CAST_ERROR, "非二维数组无法解析", e);
            }
            resArr.add(parseJsonKey(rawJson, keyMap));
        }

        System.out.println("处理后的数据 ： " + resArr);
        // 返回处理后的数据
        return new ActionTempData<>(ActionStatus.SUCCESS, resArr);
    }

    @Override
    protected void validateActionAddition() throws Exception {
        // 验证action完整性
        super.validateActionAddition();
    }

    /**
     * 替换目标json的key值
     *
     * @param rawJson 目标json
     * @param keyMap  替换规则
     * @return
     */
    public JSONObject parseJsonKey(JSONObject rawJson, JSONObject keyMap) {
        JSONObject resJsonObject = new JSONObject();
        keyMap.keySet().forEach(key -> {
            if (rawJson.get(key) != null) {
                // 若存在映射关系 ，  key -> keymap 的value  ;  value -> rawJson 的value（不变）
                resJsonObject.put(keyMap.getString(key), rawJson.getObject(key, rawJson.get(key).getClass()));
                // 删除之前的键值对
                rawJson.remove(key);
            }
        });
        if (rawJson != null && rawJson.size() > 0) resJsonObject.putAll(rawJson);

        return resJsonObject;
    }


}
