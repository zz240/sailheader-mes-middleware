package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.strategy.ActionExecute;
import com.sailheader.middleware.chain.strategy.StringPrintActionExecute;
import com.sailheader.middleware.constant.ActionStatusEnum;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        List<ActionExecute> actionExecuteList = new ArrayList<>();
        actionExecuteList.add(new StringPrintActionExecute("steven"));
        actionExecuteList.add(new StringPrintActionExecute("elizer"));

        ActionExecute actionExecute = new SingleLinerJobActionChain(actionExecuteList);
        actionExecute.dataHandler(new ActionTempData<>(ActionStatusEnum.INITIAL, "输出内容!"));
    }
}
