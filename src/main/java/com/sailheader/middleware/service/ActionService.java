package com.sailheader.middleware.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.ActionType;
import com.sailheader.middleware.constant.DataSourceType;
import com.sailheader.middleware.mapper.ActionDAO;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.ActionExample;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

@Service
public class ActionService {
    @Autowired
    private ActionDAO actionDAO;
    @Autowired
    private ApplicationContext applicationContext;

    public Action selectByPrimaryKey(Long id){
        return actionDAO.selectByPrimaryKey(id);
    }

    public int insertSelective(Action action){
        return actionDAO.insertSelective(action);
    }

    public int updateByActionStreamSelective(Action action){
        return actionDAO.updateByActionStreamSelective(action);
    }

    public int deleteActionStreamById(Long id){
        return actionDAO.deleteActionStreamById(id);
    }

    public List<Action> findAllActionsByStatus(Short status){
        ActionExample actionExample = new ActionExample();
        actionExample.createCriteria().andStatusNotEqualTo(status);
        return actionDAO.selectByExample(actionExample);
    }

    public int checkForPresence(String name){
        return actionDAO.checkForPresence(name);
    }

    public int updateByActionStream(Action action){
        return actionDAO.updateByActionStream(action);
    }

    //验证数据源参数
    public void validateDataSource(Action action) throws Exception {
        JSONObject datasource = null;
        String msg = "数据存在异常";
        if (action == null ){
            throw  MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_IS_NULL);
        }

        if (action.getType() == null || action.getType() != ActionType.REMOTE_ACCESS.getType()) {
            return;
        }

        try{
            datasource = action.getAddition();
        }catch (MiddlewareException m){
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,m);
        }catch (Exception e){
            throw new Exception(e.getMessage());
        }
        if (datasource == null){
            msg = " action 的 addition 字段为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
        if (datasource.getJSONObject("addition") == null) {
            msg = "必选字段：addition 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
        if (datasource.get("address") == null || datasource.get("address").equals("")) {
            msg = "必选字段：address 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
        if (datasource.get("name") == null || datasource.get("name").equals("")) {
            msg = "必选字段：name 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
        if (datasource.get("type") == null ){
            msg = "必选字段：type 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
        //判断动作类型
        if (DataSourceType.dataSourceMapById.get(datasource.getInteger("type")) == DataSourceType.HTTP){
            checkHttp(datasource);
        }else if (DataSourceType.dataSourceMapById.get(datasource.getInteger("type")) == DataSourceType.DB){
            checkDB(datasource);
        }else{
            throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_SUITABLE_TYPE_NOT_FOUND);
        }
    }

    private  void checkHttp(JSONObject jsonObject){
        String msg = "";
        JSONObject addition = jsonObject.getJSONObject("addition");
        if (addition.get("method") == null){
            msg = "必选字段：method 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
    }

    private void checkDB(JSONObject jsonObject){
        String msg = "";
        JSONObject json = jsonObject.getJSONObject("addition");
        if (json.get("driver") == null || json.get("driver").equals("")) {
            msg = "必选字段：driver 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
        if (json.get("username") == null || json.get("username").equals("")) {
            msg = "必选字段：username 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
        if (json.get("password") == null || json.get("password").equals("")) {
            msg = "必选字段：password 为空";
            throw MiddlewareException.getInstance(REMOTE_ACCESS_ACTION_DATASOURCE_MISSING_REQUIRE_PARAM,msg);
        }
    }

    public Object checkActionPass(Action action,String inputData) throws Exception {
        JSON json;
        ActionHandlerAdapter actionHandler;
        try {
            json = JSON.parseObject(inputData);
        }catch (Exception e){
            throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_SUITABLE_TYPE_NOT_FOUND);
        }
        ActionTempData tempData = new ActionTempData(ActionStatus.INITIAL, json);
        String beanName = ActionType.actionTypeById.get(action.getType()).getBeanName();
        try {
            actionHandler = applicationContext.getBean(beanName, ActionHandlerAdapter.class);
        } catch (BeansException exception) {
            throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_IMPL_CLASS_NOT_FOUND,exception);
        }
        actionHandler.setAction(action);
        ActionTempData data;
        try {
            data = actionHandler.dataHandler(tempData);
        }catch (MiddlewareException e){
            throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_EXECUTE_FAILURE,e);
        } catch (Exception e) {
            throw new Exception(e.getMessage());
        }
        return data.getBody();
    }
}
