package com.sailheader.middleware.chain.action.remote;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.model.Action;
import lombok.Getter;
import lombok.Setter;

public interface Access<O, I> {

    O access(ActionTempData<I> actionTempData) throws Exception;
}
