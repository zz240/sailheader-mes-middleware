package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.service.ActionService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@RestController
public class ActionController {
    @Autowired
    private ActionService actionService;

    /**
     * 添加动作
     * @param action    新增的动作对象
     * @return          结果对象
     */
    @PostMapping("/mw/api/action/stream/create")
    public OperationResult createAction(@RequestBody Action action){
        OperationResult operationResult = new OperationResult();
        String msg = "添加动作失败";
        //验证数据源参数
        actionService.validateDataSource(action);
        if (action == null && action.getName().isEmpty() && action.getType() != null){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        int i = actionService.checkForPresence(action.getName());
        if (i > 0 ){
            msg = "该名称已存在！";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        try{
            action.setCreatedOn(new Date());
            i = actionService.insertSelective(action);
        }catch (Exception e){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        return operationResult.setMessage("插入成功").setStateCode(StateCode.ADD_SUCCESS.getValue());
    }

    /**
     * 删除指定动作
     * @param id        需要删除的动作id
     * @return          删除之后的结果集
     */
    @DeleteMapping("/mw/api/action/stream/delete/{id}")
    public OperationResult deleteAction(@PathVariable Long id){
        OperationResult operationResult = new OperationResult();
        String msg = "删除失败！";
        Action action = actionService.selectByPrimaryKey(id);
        if (action != null && !action.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            action.setStatus(GeneralStatus.DELETED.getIntLabel());
            action.setModifiedOn(new Date());
            action.setName(action.getName()+ "-" +action.getId());
            actionService.updateByActionStreamSelective(action);
            return operationResult.setMessage("删除成功").setStateCode(StateCode.DELETE_SUCCESS.getValue());
        }
        return operationResult.setMessage(msg).setStateCode(StateCode.DELETE_FAIL.getValue());
    }

    /**
     * 查询指定的动作
     * @param id    需要查询的动作id
     * @return      查询之后的结果集
     */
    @GetMapping("/mw/api/action/stream/query/{id}")
    public OperationResult findActionById(@PathVariable Long id){
        Action action = actionService.selectByPrimaryKey(id);
        if (action != null && !action.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            return new OperationResult().setData(action).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.QUERY_FAIL.getValue());
    }

    /**
     * 更新指定动作的内容
     * @param id        需要更新动作的id
     * @param action    需要更新的动作内容
     * @return          更新之后的结果集
     */
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/action/stream/update/{id}")
    public OperationResult updateActionById(@PathVariable Long id,@RequestBody Action action){
        OperationResult operationResult = new OperationResult();
        String msg = "修改失败";
        //验证数据源参数
        actionService.validateDataSource(action);
        if (action.getName() == null){
            msg = "修改失败，name为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        int i = actionService.checkForPresence(action.getName());
        if (i > 0){
            msg = "修改失败,name已存在";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        Action actionStream = actionService.selectByPrimaryKey(id);
        if (actionStream == null){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (actionStream.getStatus().equals(GeneralStatus.DELETED.getIntLabel()) ){
            msg = "已删除！";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        actionStream.setName(action.getName()).setJobId(action.getJobId());
        actionStream.setType(action.getType());
        actionStream.setIndex(action.getIndex());
        actionStream.setTempData(action.getTempData());
        actionStream.setAddition(action.getAddition());
        actionStream.setModifiedBy(action.getModifiedBy());
        actionStream.setModifiedOn(new Date());
        i = actionService.updateByActionStream(actionStream);
        if (i < 1){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        msg = "修改成功";
        return operationResult.setData(actionStream).setStateCode(StateCode.EDIT_SUCCESS.getValue()).setMessage(msg);
    }

    /**
     * 查询全部动作
     * @return      返回查询所有的结果集
     */
    @GetMapping("/mw/api/action/stream/query/all")
    public OperationResult findAllActions(){
        return new OperationResult().setData(actionService.findAllActionsByStatus(GeneralStatus.DELETED.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
    }

}
