package com.sailheader.middleware.action.remote;

import com.sailheader.middleware.chain.ActionTempData;

public interface Access<O, I> {

    O access(ActionTempData<I> actionTempData) throws Exception;
}
