package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.strategy.ActionExecute;
import com.sailheader.middleware.constant.ActionStatusEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单线性调用链
 */

@Component
public class SingleLinerJobActionChain implements JobActionChain, ActionExecute {
    @Getter
    @Setter
    private List<ActionExecute> actionExecuteList;

    public SingleLinerJobActionChain(List<ActionExecute> actionExecuteList) {
        this.actionExecuteList = actionExecuteList;
    }

    @Override
    public JobActionChain addAction(ActionExecute actionExecute) {
        actionExecuteList.add(actionExecute);
        return this;
    }

    @Override
    public JobActionChain removeAction(ActionExecute actionExecute) {
        actionExecuteList.remove(actionExecute);
        return this;
    }

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> actionTempData) throws Exception {
        for (ActionExecute actionExecute : actionExecuteList) {
            // 获取动作数据
            ActionTempData<?> tempData = actionExecute.dataHandler(actionTempData);

            if (tempData.getStatus() == ActionStatusEnum.SUCCESS.getStatus()) {
                tempData.setStatus(ActionStatusEnum.INITIAL.getStatus());
                actionTempData = tempData;
            } else {
                throw new Exception("执行失败!");
            }

        }

        return actionTempData;
    }
}
