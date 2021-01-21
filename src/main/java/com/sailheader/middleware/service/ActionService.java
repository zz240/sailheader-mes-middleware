package com.sailheader.middleware.service;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.mapper.ActionDAO;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.ActionExample;
import com.sailheader.middleware.model.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.sailheader.middleware.constant.ActionType.remoteAccess;

@Service
public class ActionService {
    @Autowired
    private ActionDAO actionDAO;

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
    public void validateDataSource(Action action){
        JSONObject datasource = null;
        String msg = "数据存在异常";
        try{
            datasource = action.getAddition().getJSONObject("dataSource");
            System.out.println(datasource);
        }catch (Exception e){
            throw new RuntimeException(msg);
        }
        if (datasource == null){
            throw new RuntimeException(msg);
        }
        if (datasource.getJSONObject("addition") == null) {
            throw new RuntimeException(msg);
        }
        if (datasource.get("address") == null || datasource.get("address").equals("")) {
            throw new RuntimeException(msg);
        }
        //判断动作类型
        if (datasource.getShort("type").equals(remoteAccess.getType())){
            checkHttp(datasource);
        }else {
            checkDB(datasource);
        }
    }

    private  void checkHttp(JSONObject jsonObject){
        String msg = "数据存在异常";
        JSONObject addition = jsonObject.getJSONObject("addition");
        if (addition.get("method") == null){
            throw new RuntimeException("msg");
        }
    }

    private void checkDB(JSONObject jsonObject){
        String msg = "数据存在异常";
        JSONObject json = jsonObject.getJSONObject("addition");
        if (jsonObject.get("driver") == null || jsonObject.get("driver").equals("")) {
            throw new RuntimeException(msg);
        }
        if (jsonObject.get("username") == null || jsonObject.get("username").equals("")) {
            throw new RuntimeException(msg);
        }
        if (jsonObject.get("password") == null || jsonObject.get("password").equals("")) {
            throw new RuntimeException(msg);
        }
    }


}
