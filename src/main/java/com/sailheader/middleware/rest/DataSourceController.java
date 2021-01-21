package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.service.DataSourceService;
import com.sun.org.apache.bcel.internal.generic.IF_ACMPEQ;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@Slf4j
@RestController
public class DataSourceController {
    @Autowired
    private DataSourceService dataSourceService;

    /**
     * 添加数据源
     * @param dataSource    待添加的数据源对象
     * @return      添加之后返回的结果集
     */
    @PostMapping("/mw/api/data/source/create")
    public OperationResult createDataSource(@RequestBody DataSource dataSource){
        OperationResult operationResult = new OperationResult();
        String msg = "添加异常";
        if (dataSource == null){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getName()==null || dataSource.getName().equals("")){
            msg = "数据源名称为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSourceService.checkForPresence(dataSource.getName()) > 0){
            msg = "数据源名称重复";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getType()==null){
            msg = "数据源 type 为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getAddition() == null){
            msg = "数据源 addition 为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getAddress() == null){
            msg = "数据源 address 为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        dataSource.setCreatedOn(new Date());
        int i = dataSourceService.createDataSource(dataSource);
        if (i < 1 ){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        msg = "插入成功";
        return new OperationResult(dataSource.getName()).setMessage("插入成功").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 删除数据源
     * @param id    待删除的数据源id
     * @return      删除之后返回的结果集
     */
    @DeleteMapping("/mw/api/data/source/delete/{id}")
    public OperationResult deleteDataSource(@PathVariable Long id){
        DataSource dataSource = dataSourceService.findDataSourceByid(id);
        OperationResult operationResult = new OperationResult();
        String msg = "删除异常";

        if (dataSource==null){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.DELETE_FAIL.getValue());
        }
        if (dataSource.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            msg = "数据源已删除";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.DELETE_FAIL.getValue());
        }

        dataSource.setStatus(GeneralStatus.DELETED.getIntLabel()).setName(dataSource.getName()+"-"+dataSource.getId());
        dataSourceService.updateDataSource(dataSource);
        msg = "删除成功！";
        return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 查询指定的数据源
     * @param id    执行的数据源id
     * @return      查询之后返回的结果集
     */
    @GetMapping("/mw/api/data/source/query/{id}")
    public OperationResult findDataSourceById(@PathVariable Long id){
        OperationResult operationResult = new OperationResult();
        String msg = "查询异常";
        DataSource dataSource = dataSourceService.findDataSourceByid(id);
        if (dataSource == null){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.QUERY_FAIL.getValue());
        }
        if (dataSource.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            msg = "数据源已删除！";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.QUERY_FAIL.getValue());
        }

        return operationResult.setData(dataSource).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 更新数据源
     * @param id    待更新的数据源id
     * @param dataSource    更新数据源内容
     * @return      更新之后返回的结果集
     */
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/data/source/update/{id}")
    public OperationResult updateDataSource(@PathVariable Long id,@RequestBody DataSource dataSource){
        OperationResult operationResult = new OperationResult();
        String msg = "更新失败";
        DataSource source = dataSourceService.findDataSourceByid(id);
        if (source == null){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (source.getStatus().equals(GeneralStatus.DELETED.getIntLabel())){
            msg = "数据源已删除";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource == null){
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource.getName()==null || dataSource.getName().equals("")){
            msg = "数据源名称为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSourceService.checkForPresence(dataSource.getName()) > 0){
            if (!dataSource.getName().equals(source.getName())){
                msg = "数据源名称重复";
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
            }
        }
        if (dataSource.getType()==null){
            msg = "数据源 type 为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource.getAddition() == null){
            msg = "数据源 addition 为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource.getAddress() == null){
            msg = "数据源 address 为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        // TODO  mvc抛出 json无法解析的异常 updateDataSourceTest08
        source.setName(dataSource.getName()).setAddress(dataSource.getAddress()).setAddition(dataSource.getAddition())
                .setType(dataSource.getType()).setModifiedBy(dataSource.getModifiedBy()).setModifiedOn(new Date());
        try {
            dataSourceService.updateDataSource(source);
        }catch (Exception e){
            throw new RuntimeException("更新异常！");
//            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }

        return operationResult.setData(source).setMessage("更新成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 查询所有数据源
     * @return  返回查询所有数据源的结果集
     */
    @GetMapping("/mw/api/data/source/query/all")
    public OperationResult findDatasourcesByStatus(){
        return new OperationResult().setData(dataSourceService.findJobByStatus(GeneralStatus.DELETED.getIntLabel())).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }
}
