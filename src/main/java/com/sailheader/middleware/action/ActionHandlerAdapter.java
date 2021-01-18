package com.sailheader.middleware.action;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.common.helper.MappingJsonData;
import com.sailheader.middleware.model.Action;
import lombok.Getter;
import lombok.Setter;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 泛型适配器
 *
 * @param <O>
 * @param <I>
 */

public abstract class ActionHandlerAdapter<O, I> implements ActionHandler {
    @Setter
    @Getter
    protected Action action;


    @Override
    public ActionTempData<O> dataHandler(ActionTempData<?> object) throws Exception {
        // 0. 验证 action 参数有效性
        simpleValidateActionData();

        /**
         * 1. 校验输入内容并转换为泛型内容(? 可以是任何类型转为 I,
         * 例如 ActionTempData<String> => ActionTempData<Integer> )
         * TODO
         */
        ActionTempData<I> sendData = (ActionTempData<I>) object;
        I inputData = sendData != null && sendData.getBody() != null ? sendData.getBody() : null;

        // 2. 替换action addition中的占位符内容
        if (action.getAddition() != null && inputData != null) {
            action.setAddition((JSONObject) filterForActionAddition(inputData));
            // 校验 action addition 的内容
            validateActionAddition();
        }

        // 3. 执行并返回内容
        ActionTempData<O> actionTempData = this.execute(sendData);
        O currentData = actionTempData != null && actionTempData.getBody() != null ? actionTempData.getBody() : null;

        // 4. 过滤输出内容，替换其中的占位符
        if (action.getTempData() != null && actionTempData != null) {
            actionTempData.setBody((O) filterForOut(inputData, currentData));
        }

        return actionTempData;
    }

    // 实际执行需要实现的内容
    protected abstract ActionTempData<O> execute(ActionTempData<I> object) throws Exception;

    /**
     * 过滤action中的addition数据,将其中带有占位符的部分替换
     * 使用json过滤
     * 如果其实现子类有其他的过滤方式则需要自己实现
     *
     * @param lastData 上一个动作输入的数据
     * @return 根据 tempData 格式替换后产生的数据
     */
    protected JSON filterForActionAddition(I lastData) {
        Map<String, Object> map = new HashMap<>();
        map.put("lastData", lastData);
        return MappingJsonData.mappingJsonIntoJson(map, action.getAddition());
    }

    // 每个动作处理器必须验证 动作 数据
    protected abstract void validateActionAddition() throws Exception;


    /**
     * 过滤输出数据将输出数据的内容根据格式替换
     * 默认使用json过滤
     * 如果其实现子类有其他的过滤方式则需要自己实现
     *
     * @param lastData    上一个动作输入的数据
     * @param currentData 当前动作产生的数据
     * @return 根据 tempData 格式替换后产生的数据
     */
    protected JSON filterForOut(I lastData, O currentData) {
        Map<String, Object> map = new HashMap<>();
        if (lastData != null) map.put("lastData", lastData);
        if (currentData != null) map.put("current", currentData);
        return MappingJsonData.mappingJsonIntoJson(map, action.getTempData());
    }

    /**
     * 验证 action data 参数是否完整
     */
    private void simpleValidateActionData() throws Exception {
        if (action == null) {
            throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_IS_NULL);
        }
        if (action.getJobId() == null || action.getJobId() == 0
                || action.getName() == null || action.getName().length() == 0
                || action.getType() == null || action.getType() == 0
                || action.getIndex() == 0) {
            throw MiddlewareException
                    .getInstance(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM);
        }
    }
}
