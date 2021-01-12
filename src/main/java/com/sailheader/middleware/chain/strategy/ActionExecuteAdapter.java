package com.sailheader.middleware.chain.strategy;

import com.sailheader.middleware.chain.ActionTempData;
import org.json.JSONObject;

public abstract class ActionExecuteAdapter<O, I> implements ActionExecute{

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> object) throws Exception {

        ActionTempData<I> sendData = (ActionTempData<I>) object;
        if (sendData == null) {
            throw new Exception("数据类型不一致!");
        }

        return this.execute(sendData);
    }

    public abstract ActionTempData<O> execute(ActionTempData<I> object);
}
