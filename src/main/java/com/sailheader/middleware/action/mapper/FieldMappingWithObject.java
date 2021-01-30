package com.sailheader.middleware.action.mapper;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.action.FieldMappingActionHandler;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.constant.ActionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Slf4j
@Component("action.field.mapping.object")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FieldMappingWithObject extends FieldMappingActionHandler<JSON, JSON> {


    @Override
    protected ActionTempData<JSON> mapping(ActionTempData<JSON> actionTempData) {
        return new ActionTempData<>(ActionStatus.SUCCESS, action.getAddition());
    }

    @Override
    protected void validateActionAddition() throws Exception {
        super.validateActionAddition();
    }
}
