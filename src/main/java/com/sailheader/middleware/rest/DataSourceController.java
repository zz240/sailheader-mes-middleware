package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.service.DataSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
public class DataSourceController {
    @Autowired
    private DataSourceService dataSourceService;

    @PostMapping("/wm/api/data/source/create")
    public OperationResult createDataSource(@RequestBody DataSource dataSource){
        int i = dataSourceService.createDataSource(dataSource);
        return new OperationResult(dataSource.getName()).setMessage("插入成功").setStateCode(StateCode.ADD_SUCCESS.getValue());
    }

    @DeleteMapping("/wm/api/data/source/delete/{id}")
    public OperationResult deleteDataSource(@PathVariable Long id){
        DataSource dataSource = dataSourceService.findDataSourceByid(id);
        if (dataSource != null && !dataSource.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            dataSource.setStatus(GeneralStatus.DELETED.getIntLabel());
            dataSourceService.updateDataSource(dataSource);
            return new OperationResult(id).setMessage("删除成功").setStateCode(StateCode.DELETE_SUCCESS.getValue());
        }
        return new OperationResult().setMessage("删除失败,已删除或不存在！").setStateCode(StateCode.DELETE_FAIL.getValue());
    }

    @GetMapping("/wm/api/data/source/query/{id}")
    public OperationResult findDataSourceById(@PathVariable Long id){
        DataSource dataSource = dataSourceService.findDataSourceByid(id);
        if (dataSource != null && !dataSource.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            return new OperationResult().setData(dataSource).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("查询失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/wm/api/data/source/update/{id}")
    public OperationResult updateDataSource(@PathVariable Long id,@RequestBody DataSource dataSource){
        DataSource source = dataSourceService.findDataSourceByid(id);
        if (source != null && !dataSource.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            source.setName(dataSource.getName());
            source.setAddress(dataSource.getAddress());
            source.setAddition(dataSource.getAddition());
            source.setType(dataSource.getType());
            dataSourceService.updateDataSource(source);
            return new OperationResult(source.getName()).setMessage("更新成功！").setStateCode(StateCode.EDIT_SUCCESS.getValue());
        }
        return new OperationResult().setData(null).setMessage("更新失败！").setStateCode(StateCode.EDIT_FAIL.getValue());
    }

    @GetMapping("/wm/api/data/source/query/all")
    public OperationResult findDatasourcesByStatus(){
        return new OperationResult().setData(dataSourceService.findJobByStatus(GeneralStatus.DELETED.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.QUERY_SUCCESS.getValue());
    }





}
