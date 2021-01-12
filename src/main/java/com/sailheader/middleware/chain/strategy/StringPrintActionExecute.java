package com.sailheader.middleware.chain.strategy;

import com.sailheader.middleware.chain.ActionTempData;

public class StringPrintActionExecute implements ActionExecute{

    private String name;

    public StringPrintActionExecute(String name) {
        this.name = name;
    }

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> object) throws Exception {
        String str = (String) object.getBody();
        System.out.println(name + str);
        return new ActionTempData<>(1, name + str);
    }
}
