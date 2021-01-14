package com.sailheader.middleware.chain.action.remote;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;

public interface Access<O, I> {
    O access(ActionTempData<I> actionTempData) throws Exception;
}
