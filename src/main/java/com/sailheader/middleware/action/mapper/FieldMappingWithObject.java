package com.sailheader.middleware.action.mapper;

import com.sailheader.middleware.action.FieldMappingActionHandler;
import com.sailheader.middleware.chain.ActionTempData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Slf4j
@Component("action.field.mapping.object")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FieldMappingWithObject extends FieldMappingActionHandler {


    @Override
    protected ActionTempData mapping(ActionTempData actionTempData) {
        return null;
    }

    @Override
    protected void validateActionAddition() throws Exception {
        super.validateActionAddition();
    }
}
