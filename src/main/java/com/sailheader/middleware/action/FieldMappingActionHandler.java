package com.sailheader.middleware.action;

import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.REMOTE_ACCESS_ACTION_DATASOURCE_IS_NULL;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM;

/**
 * 字段映射动作
 */
public abstract class FieldMappingActionHandler<O, I> extends ActionHandlerAdapter<O, I> {

    @Override
    protected ActionTempData<O> execute(ActionTempData<I> object) throws Exception {
        return mapping(object);
    }

    protected abstract ActionTempData<O> mapping(ActionTempData<I> actionTempData);

    @Override
    protected void validateActionAddition() throws Exception {
        if (action == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_IS_NULL);
        }
        if (action.getIndex() == 0) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM, "action 中缺少 index 参数");
        }
        if (action.getName() == null || action.getName().equals("")) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM, "action 中缺少 name 参数");
        }
        if (action.getType() == null || action.getType() == 0) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM, "action 中缺少 type 参数");
        }
        if (action.getJobId() == null) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM, "action 中缺少 address 参数");
        }
        if (action.getAddition() == null) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM, "action 中缺少 addition 参数 ");
        }
    }
}
