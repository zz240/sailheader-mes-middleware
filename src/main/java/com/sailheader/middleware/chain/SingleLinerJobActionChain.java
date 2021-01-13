package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.action.Action;
import com.sailheader.middleware.constant.ActionStatusEnum;
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
public class SingleLinerJobActionChain implements JobActionChain, Action {
    @Getter
    @Setter
    private List<Action> actionList;

    public SingleLinerJobActionChain(List<Action> actionList) {
        this.actionList = actionList;
    }

    @Override
    public JobActionChain addAction(Action action) {
        actionList.add(action);
        return this;
    }

    @Override
    public JobActionChain removeAction(Action action) {
        actionList.remove(action);
        return this;
    }

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> actionTempData) throws Exception {
        for (Action action : actionList) {
            // 获取动作数据
            ActionTempData<?> tempData = action.dataHandler(actionTempData);

            if (tempData.getStatus() == ActionStatusEnum.SUCCESS) {
                tempData.setStatus(ActionStatusEnum.INITIAL);
                actionTempData = tempData;
            } else {
                throw new Exception("执行失败!");
            }

        }

        return actionTempData;
    }
}
