package com.sailheader.middleware.chain;

import com.sailheader.middleware.chain.action.Action;
import com.sailheader.middleware.chain.action.StringPrintAction;
import com.sailheader.middleware.constant.ActionStatusEnum;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        List<Action> actionList = new ArrayList<>();
        actionList.add(new StringPrintAction("steven"));
        actionList.add(new StringPrintAction("elizer"));

        Action action = new SingleLinerJobActionChain(actionList);
        action.dataHandler(new ActionTempData<>(ActionStatusEnum.INITIAL, "输出内容!"));
    }
}
