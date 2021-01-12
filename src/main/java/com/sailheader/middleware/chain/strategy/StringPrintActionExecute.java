package com.sailheader.middleware.chain.strategy;

import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.constant.ActionStatusEnum;

public class StringPrintActionExecute extends ActionExecuteAdapter<String, String>{

    private String name;

    public StringPrintActionExecute(String name) {
        this.name = name;
    }

    @Override
    public ActionTempData<String> execute(ActionTempData<String> object) {
        String str = (String) object.getBody();
        System.out.println(name + str);
        return new ActionTempData<>(ActionStatusEnum.SUCCESS, name + str);
    }
}
