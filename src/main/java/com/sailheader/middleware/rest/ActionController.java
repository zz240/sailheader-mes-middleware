package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.service.ActionService;
import com.sun.org.apache.bcel.internal.generic.IF_ACMPEQ;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
public class ActionController {
    @Autowired
    private ActionService actionService;

    /**
     * 添加动作
     *
     * @param action 新增的动作对象
     * @return 结果对象
     */
    @PostMapping("/mw/api/action/stream/create")
    public OperationResult createAction(@RequestBody Action action) {
        OperationResult operationResult = new OperationResult();
        String msg = "添加动作失败";
        //验证数据源参数
        try{
            actionService.validateDataSource(action);
        }catch (Exception e){
            log.info(msg +" - "+e.getMessage());
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }

        if (action == null && action.getName().isEmpty() && action.getType() != null) {
            log.info("添加动作失败");
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        int i = actionService.checkForPresence(action.getName());
        if (i > 0) {
            msg = "该名称已存在！";
            log.info("添加动作失败");
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        try {
            action.setCreatedOn(new Date());
            i = actionService.insertSelective(action);
        } catch (Exception e) {
            log.info("添加动作失败");
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        log.info("添加任务"+action.getName()+"成功！");
        return operationResult.setData(action.getName()).setMessage("插入成功").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 删除指定动作
     *
     * @param id 需要删除的动作id
     * @return 删除之后的结果集
     */
    @DeleteMapping("/mw/api/action/stream/delete/{id}")
    public OperationResult deleteAction(@PathVariable Long id) {
        OperationResult operationResult = new OperationResult();
        String msg = "删除失败！";
        Action action = actionService.selectByPrimaryKey(id);
        if (action == null) {
            log.info("删除失败，动作为空");
            return operationResult.setMessage(msg).setStateCode(StateCode.DELETE_FAIL.getValue());
        }
        if (action.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            msg = "该动作已删除！";
            log.info("删除失败，动作已删除");
            return operationResult.setMessage(msg).setStateCode(StateCode.DELETE_FAIL.getValue());
        }

        action.setStatus(GeneralStatus.DELETED.getIntLabel());
        action.setModifiedOn(new Date());
        action.setName(action.getName() + "-" + action.getId());
        actionService.updateByActionStreamSelective(action);
        msg = "删除成功";
        log.info("删除"+action.getName()+"成功");
        return operationResult.setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 查询指定的动作
     *
     * @param id 需要查询的动作id
     * @return 查询之后的结果集
     */
    @GetMapping("/mw/api/action/stream/query/{id}")
    public OperationResult findActionById(@PathVariable Long id) {
        Action action = actionService.selectByPrimaryKey(id);
        if (action != null && !action.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            log.info("查询id为："+id+"的动作");
            return new OperationResult().setData(action).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
        }
        log.info("查询id为"+id+" 的动作失败，不存在或已删除！");
        return new OperationResult().setData(null).setMessage("查询失败，不存在或已删除！").setStateCode(StateCode.QUERY_FAIL.getValue());
    }

    /**
     * 更新指定动作的内容
     *
     * @param id     需要更新动作的id
     * @param action 需要更新的动作内容
     * @return 更新之后的结果集
     */
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/action/stream/update/{id}")
    public OperationResult updateActionById(@PathVariable Long id, @RequestBody Action action) {
        OperationResult operationResult = new OperationResult();
        String msg = "修改动作失败";
        //验证数据源参数
        try{
            actionService.validateDataSource(action);
        }catch (Exception e){
            log.info(msg +" : "+ e.getMessage());
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (action.getName() == null) {
            msg = "修改动作失败，name为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        Action actionStream = actionService.selectByPrimaryKey(id);
        int i = actionService.checkForPresence(action.getName());
        if (i > 0) {
            if (!actionStream.getName().equals(action.getName())){
                msg = "修改动作失败,name已存在";
                log.info(msg);
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
            }
        }
        if (actionStream == null) {
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (actionStream.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            msg = "已删除！";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        actionStream.setName(action.getName()).setJobId(action.getJobId()).setType(action.getType()).setIndex(action.getIndex())
                .setTempData(action.getTempData()).setAddition(action.getAddition()).setModifiedBy(action.getModifiedBy()).setModifiedOn(new Date());
        try {
            i = actionService.updateByActionStream(actionStream);
        }catch (Exception e){
            log.info("更新动作异常 :" + e.getMessage());
            throw new RuntimeException("更新异常！");
        }
        if (i < 1) {
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        msg = "修改成功";
        log.info(msg);
        return operationResult.setData(actionStream).setStateCode(StateCode.SYSTEM_SUCCESS.getValue()).setMessage(msg);
    }

    /**
     * 查询全部动作
     *
     * @return 返回查询所有的结果集
     */
    @GetMapping("/mw/api/action/stream/query/all")
    public OperationResult findAllActions() {
        return new OperationResult().setData(actionService.findAllActionsByStatus(GeneralStatus.DELETED.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 检测 动作能否执行成功
     * @param
     * @return
     */
    @PostMapping("/mw/api/action/stream/check")
    public OperationResult checkActionPass(@RequestBody Map<String, Object> map){
        OperationResult operationResult = new OperationResult();
        ObjectMapper objectMapper = new ObjectMapper();
        String inputData;
        String msg = "动作测试执行失败";

        //转换 action 对象
        Action action = objectMapper.convertValue(map.get("action"), Action.class);

        try{
            inputData = JSON.toJSONString(map.get("inputData"));
        } catch (Exception e) {
            log.info(msg + e.getMessage());
            return operationResult.setMessage(msg).setStateCode(StateCode.SYSTEM_FAIL.getValue());
        }

        if (map.get("inputData") != null && inputData == null) {
            msg = "数据转换异常";
            log.info(msg);
            return operationResult.setMessage(msg).setStateCode(StateCode.SYSTEM_FAIL.getValue());
        }

        if (action == null){
            msg = "动作为空";
            log.info(msg);
            return operationResult.setMessage(msg).setStateCode(StateCode.SYSTEM_FAIL.getValue());
        }
        Object result;
        try {
            result = actionService.checkActionPass(action, inputData);
        } catch (MiddlewareException e){
            log.info(msg+"："+e.getMessage());
            return operationResult.setMessage(msg).setStateCode(StateCode.SYSTEM_FAIL.getValue());
        } catch (Exception e) {
            log.info(msg+"："+e.getMessage());
            return operationResult.setMessage(msg).setStateCode(StateCode.SYSTEM_FAIL.getValue());
        }
        msg = "action: 测试执行成功";
        log.info(msg);
        return operationResult.setData(result).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }
}
