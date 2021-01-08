package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.EnterpriseRouteMapping;
import com.sailheader.middleware.service.EnterpriseRouteMappingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
public class EnterpriseRouteMappingController {
    @Autowired
    private EnterpriseRouteMappingService enterpriseRouteMappingService;

    @PostMapping("/api/enterprise/route/data/complete/insert")
    public OperationResult insertEnterpriseRoute(@RequestBody EnterpriseRouteMapping enterpriseRouteMapping){
        Integer insert = enterpriseRouteMappingService.insert(enterpriseRouteMapping);
        return new OperationResult().setData(enterpriseRouteMapping).setMessage("添加成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    @GetMapping("/api/enterprise/route/data/{id}/delete")
    public OperationResult deleteEnterpriseRouteById(@PathVariable Long id){
        Integer delete = enterpriseRouteMappingService.deleteByPrimaryKey(id);
        return new OperationResult().setData(null).setMessage("删除成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    @GetMapping("/api/enterprise/route/data/{id}/update")
    public OperationResult updateByPrimaryKey(@PathVariable Long id,/*@RequestBody */EnterpriseRouteMapping enterpriseRouteMapping){
        EnterpriseRouteMapping data = enterpriseRouteMapping.setEnterpriseName("abctest").setTargetAddress("http://dsa.dsada.com").setCode("hdsdfqtestcode");

        EnterpriseRouteMapping result = enterpriseRouteMappingService.selectByPrimaryKey(id);
        if (result != null){
            Integer integer = enterpriseRouteMappingService.updateByPrimaryKey(data);
            return new OperationResult().setData(result).setMessage("更新成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("更新失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }




}
