package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.service.ActionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@RestController
public class ActionController {
    @Autowired
    private ActionService actionService;

    @PostMapping("/mw/api/action/stream/create")
    public OperationResult createAction(@RequestBody Action action){
        action.setCreatedOn(new Date());
        int i = actionService.insertSelective(action);
        return new OperationResult(action).setMessage("插入成功").setStateCode(StateCode.ADD_SUCCESS.getValue());
    }

    @DeleteMapping("/mw/api/action/stream/delete/{id}")
    public OperationResult deleteAction(@PathVariable Long id){
        Action action = actionService.selectByPrimaryKey(id);
        if (action != null && !action.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            action.setStatus(GeneralStatus.DELETED.getIntLabel());
            action.setModifiedOn(new Date());
            actionService.updateByActionStreamSelective(action);
            return new OperationResult(id).setMessage("删除成功").setStateCode(StateCode.DELETE_SUCCESS.getValue());
        }
        return new OperationResult().setMessage("删除失败,已删除或不存在！").setStateCode(StateCode.DELETE_FAIL.getValue());
    }

    @GetMapping("/mw/api/action/stream/query/{id}")
    public OperationResult findActionById(@PathVariable Long id){
        Action action = actionService.selectByPrimaryKey(id);
        if (action != null && !action.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            return new OperationResult().setData(action).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/action/stream/update/{id}")
    public OperationResult updateActionById(@PathVariable Long id,@RequestBody Action action){
        Action actionStream = actionService.selectByPrimaryKey(id);
        if (actionStream != null && !actionStream.getStatus().equals(GeneralStatus.DELETED.getIntLabel()) ){
            actionStream.setName(action.getName());
            actionStream.setJobId(action.getJobId());
            actionStream.setType(action.getType());
            actionStream.setIndex(action.getIndex());
            actionStream.setTempData(action.getTempData());
            actionStream.setAddition(action.getAddition());
            actionStream.setModifiedBy(action.getModifiedBy());
            actionStream.setModifiedOn(new Date());
            int i = actionService.updateByActionStreamSelective(actionStream);
            return new OperationResult(actionStream).setMessage("更新成功！").setStateCode(StateCode.EDIT_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("更新失败！").setStateCode(StateCode.EDIT_FAIL.getValue());
    }

    @GetMapping("/mw/api/action/stream/query/all")
    public OperationResult findAllActions(){
        return new OperationResult().setData(actionService.findAllActionsByStatus(GeneralStatus.DELETED.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
    }

}
