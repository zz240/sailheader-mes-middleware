package com.sailheader.middleware.chain.action;

import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.constant.ActionStatus;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class StringPrintActionHandler extends ActionHandlerAdapter<String, String> {

    @Setter
    @Getter
    private String name;

    public StringPrintActionHandler() {
    }

    public StringPrintActionHandler(String name) {
        this.name = name;
    }

    @Override
    public ActionTempData<String> execute(ActionTempData<String> object) {
        String str = (String) object.getBody();
        System.out.println(name + str);
        return new ActionTempData<>(ActionStatus.SUCCESS, name + str);
    }

    @Override
    protected void validateActionData() throws Exception {

    }
}
