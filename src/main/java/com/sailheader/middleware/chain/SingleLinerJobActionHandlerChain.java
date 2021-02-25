package com.sailheader.middleware.chain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.common.helper.MappingJsonData;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

/**
 * 单线性调用链
 */

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SingleLinerJobActionHandlerChain implements JobActionChain<ActionHandlerAdapter<?, ?>> {

    @Getter
    @Setter
    private SingleLinerChainContext singleLinerChainContext;

    {
        singleLinerChainContext = new SingleLinerChainContext();
    }

    @Getter
    @Setter
    private List<ActionHandlerAdapter<?, ?>> actionHandlerList;

    public SingleLinerJobActionHandlerChain(List<ActionHandlerAdapter<?, ?>> actionHandlerList) {
        this.actionHandlerList = actionHandlerList;
    }

    @Override
    public JobActionChain<ActionHandlerAdapter<?, ?>> addAction(ActionHandlerAdapter<?, ?> actionHandler) {
        actionHandlerList.add(actionHandler);
        return this;
    }

    @Override
    public JobActionChain<ActionHandlerAdapter<?, ?>> removeAction(ActionHandlerAdapter<?, ?> actionHandler) {
        actionHandlerList.remove(actionHandler);
        return this;
    }

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> actionTempData) throws Exception {
        if (actionTempData == null) {
            actionTempData = new ActionTempData<>(ActionStatus.INITIAL, null);
        }
        // 将第一个输入数据加入当前上下文
        singleLinerChainContext.setChainContextItem(0, actionTempData);

        for (ActionHandlerAdapter<?, ?> actionHandlerAdapter : actionHandlerList) {
            // 帮助构造每个动作的数据
            preActionDataBeforeExecute(actionHandlerAdapter);

            // 获取动作数据
            ActionTempData<?> tempData;
            try {
                tempData = actionHandlerAdapter.dataHandler(actionTempData);
            } catch (Exception e) {
                throw MiddlewareException
                        .getInstance(SINGLE_ACTION_CHAIN_EXCEPTION, "动作序号:" + actionHandlerAdapter.getAction() + "执行失败!", e);
            }

            // 将所有输出数据加入当前上下文
            singleLinerChainContext
                    .setChainContextItem(actionHandlerAdapter.getAction().getIndex(), tempData);

            if (tempData == null || tempData.getStatus() == null) {
                throw MiddlewareException.getInstance(JOB_ACTION_CHAIN_EXECUTE_INTERRUPT,
                                                      "动作序号:" + actionHandlerAdapter.getAction()
                                                                                    .getIndex() + "执行失败");
            } else if (tempData.getStatus() == ActionStatus.SUCCESS) {
                tempData.setStatus(ActionStatus.INITIAL);
                actionTempData = tempData;
            } else {
                actionTempData = tempData;
                throw MiddlewareException.getInstance(JOB_ACTION_CHAIN_EXECUTE_INTERRUPT,
                                                      "动作序号:" + actionHandlerAdapter.getAction()
                                                                                    .getIndex() + "执行失败");
            }

        }

        return actionTempData;
    }

    /**
     * 在执行之前预处理 action 需要的数据
     */
    @Override
    public void preActionDataBeforeExecute(ActionHandlerAdapter<?, ?> actionHandlerAdapter) throws Exception {
        // 1. 替换 addition 占位符
        Action action = actionHandlerAdapter.getAction();
        if (action.getAddition() == null) {
            return;
        }

        Map<String, Object> map = new HashMap<>();
        map.put("contextData", singleLinerChainContext.getCollection());
        JSONObject json = (JSONObject) MappingJsonData.groovyJsonMapping(map, action.getAddition());
        action.setAddition(json);
    }


    private static class SingleLinerChainContext implements ChainContext<Map<Integer, ActionTempData<?>>, Integer, ActionTempData<?>> {

        Map<Integer, ActionTempData<?>> actionTempDataList = new HashMap<>();


        @Override
        public void setChainContextItem(Integer key, ActionTempData<?> context) {
            // 深复制
            ActionTempData<?> inputData;
            try {
                inputData = (ActionTempData<?>) JSON.parseObject(JSON.toJSONString(context), ActionTempData.class);
            } catch (Exception e) {
                throw MiddlewareException
                        .getInstance(JOB_ACTION_CHAIN_CONTEXT_DATA_COPY_ERROR, "动作序号:" + key + "执行失败！--" + e.getMessage());
            }
            actionTempDataList.put(key, inputData);
        }

        @Override
        public ActionTempData<?> getItem(Integer key) {
            return actionTempDataList.get(key);
        }

        @Override
        public Map<Integer, ActionTempData<?>> getCollection() {
            return actionTempDataList;
        }

    }
}
