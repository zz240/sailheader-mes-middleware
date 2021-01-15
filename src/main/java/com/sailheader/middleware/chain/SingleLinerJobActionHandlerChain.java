package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.action.ActionHandler;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单线性调用链
 */

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SingleLinerJobActionHandlerChain implements JobActionChain, ActionHandler {
    @Getter
    @Setter
    private List<ActionHandler> actionHandlerList;

    public SingleLinerJobActionHandlerChain(List<ActionHandler> actionHandlerList) {
        this.actionHandlerList = actionHandlerList;
    }

    @Override
    public JobActionChain addAction(ActionHandler actionHandler) {
        actionHandlerList.add(actionHandler);
        return this;
    }

    @Override
    public JobActionChain removeAction(ActionHandler actionHandler) {
        actionHandlerList.remove(actionHandler);
        return this;
    }

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> actionTempData) throws Exception {
        for (ActionHandler actionHandler : actionHandlerList) {
            // 获取动作数据
            ActionTempData<?> tempData = actionHandler.dataHandler(actionTempData);

            if (tempData.getStatus() == ActionStatus.SUCCESS) {
                tempData.setStatus(ActionStatus.INITIAL);
                actionTempData = tempData;
            } else {
                throw new Exception("执行失败!");
            }

        }

        return actionTempData;
    }
}
