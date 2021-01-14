package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.action.ActionHandler;
import com.sailheader.middleware.chain.action.StringPrintActionHandler;
import com.sailheader.middleware.constant.ActionStatus;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        List<ActionHandler> actionHandlerList = new ArrayList<>();
        actionHandlerList.add(new StringPrintActionHandler("steven"));
        actionHandlerList.add(new StringPrintActionHandler("elizer"));

        ActionHandler actionHandler = new SingleLinerJobActionHandlerChain(actionHandlerList);
        actionHandler.dataHandler(new ActionTempData<>(ActionStatus.INITIAL, "输出内容!"));
    }
}
