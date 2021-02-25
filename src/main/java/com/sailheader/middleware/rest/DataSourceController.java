package com.sailheader.middleware.rest;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.service.IDataSourceService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@Slf4j
@RestController
public class DataSourceController {
    @Autowired
    private IDataSourceService dataSourceService;

    /**
     * 添加数据源
     *
     * @param dataSource 待添加的数据源对象
     * @return 添加之后返回的结果集
     */
    @PostMapping("/mw/api/data/source/create")
    public OperationResult<String> createDataSource(@RequestBody DataSource dataSource) {
        OperationResult<String> operationResult = new OperationResult<>();
        String msg = "添加异常";
        if (dataSource == null) {
            msg = msg + "，数据源为空";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getName() == null || dataSource.getName().equals("")) {
            msg = "数据源名称为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSourceService.checkForPresence(dataSource) > 0) {
            msg = "数据源名称重复";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getType() == null) {
            msg = "数据源 type 为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getAddition() == null) {
            msg = "数据源 addition 为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        if (dataSource.getAddress() == null) {
            msg = "数据源 address 为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        dataSource.setCreatedOn(new Date());

        int i;
        try {
            i = dataSourceService.createDataSource(dataSource);
        } catch (Exception e) {
            log.info(e.getMessage());
            return operationResult.setData(null).setMessage(msg+e.getMessage()).setStateCode(StateCode.ADD_FAIL.getValue());
        }

        if (i < 1) {
            msg = "操作失败,添加到数据库异常";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.ADD_FAIL.getValue());
        }
        msg = "插入成功";
        log.info(msg);
        return operationResult.setData(dataSource.getName()).setMessage(msg)
                              .setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 删除数据源
     *
     * @param id 待删除的数据源id
     * @return 删除之后返回的结果集
     */
    @DeleteMapping("/mw/api/data/source/delete/{id}")
    public OperationResult<String> deleteDataSource(@PathVariable Long id) {
        DataSource dataSource = dataSourceService.findDataSourceByid(id);
        OperationResult<String> operationResult = new OperationResult<>();
        String msg = "删除异常 ";

        if (dataSource == null) {
            msg = msg +"找不到数据源";
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.DELETE_FAIL.getValue());
        }
        if (dataSource.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            msg = "数据源已删除";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.DELETE_FAIL.getValue());
        }
        try {
            dataSourceService.deleteDataSourceById(dataSource);
        } catch (Exception e) {
            log.info(e.getMessage());
            return operationResult.setData(null).setMessage(msg+e.getMessage()).setStateCode(StateCode.DELETE_FAIL.getValue());
        }

        msg = "成功删除数据源！";
        log.info(msg);
        return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 查询指定的数据源
     *
     * @param id 执行的数据源id
     * @return 查询之后返回的结果集
     */
    @GetMapping("/mw/api/data/source/query/{id}")
    public OperationResult<DataSource> findDataSourceById(@PathVariable Long id) {
        OperationResult<DataSource> operationResult = new OperationResult<>();
        String msg = "查询异常";
        DataSource dataSource;
        try {
            dataSource = dataSourceService.findDataSourceByid(id);
        } catch (Exception e) {
            log.info(e.getMessage());
            return operationResult.setData(null).setMessage(msg+e.getMessage()).setStateCode(StateCode.QUERY_FAIL.getValue());
        }

        if (dataSource == null) {
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.QUERY_FAIL.getValue());
        }
        if (dataSource.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            msg = "数据源已删除！";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.QUERY_FAIL.getValue());
        }
        log.info("查询id为" + id + " 的数据源");
        return operationResult.setData(dataSource).setMessage("查询成功！")
                              .setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 更新数据源
     *
     * @param id         待更新的数据源id
     * @param dataSource 更新数据源内容
     * @return 更新之后返回的结果集
     */
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/mw/api/data/source/update/{id}")
    public OperationResult<DataSource> updateDataSource(@PathVariable Long id, @RequestBody DataSource dataSource) {
        OperationResult<DataSource> operationResult = new OperationResult<>();
        String msg = "更新失败 ";
        DataSource source = dataSourceService.findDataSourceByid(id);
        if (source == null) {
            msg = msg + "找不到要修改的数据源";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (source.getStatus().equals(GeneralStatus.DELETED.getIntLabel())) {
            msg = "数据源已删除";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource == null) {
            log.info(msg+"，请填写更新内容");
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource.getName() == null || dataSource.getName().equals("")) {
            msg = "数据源名称为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSourceService.checkForPresence(dataSource) > 0) {
            if (!dataSource.getName().equals(source.getName())) {
                msg = "数据源名称重复";
                log.info(msg);
                return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
            }
        }
        if (dataSource.getType() == null) {
            msg = "数据源 type 为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource.getAddition() == null) {
            msg = "数据源 addition 为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        if (dataSource.getAddress() == null) {
            msg = "数据源 address 为空";
            log.info(msg);
            return operationResult.setData(null).setMessage(msg).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        source.setName(dataSource.getName()).setAddress(dataSource.getAddress()).setAddition(dataSource.getAddition())
              .setType(dataSource.getType()).setModifiedBy(dataSource.getModifiedBy()).setModifiedOn(new Date());
        try {
            dataSourceService.updateDataSource(source);
        } catch (Exception e) {
            log.info("更新数据源： " + source + " 异常 : " + e.getMessage());
//            throw new RuntimeException("更新异常！");
            return operationResult.setData(null).setMessage("更新数据源： " + source + " 异常 : " + e.getMessage()).setStateCode(StateCode.EDIT_FAIL.getValue());
        }
        log.info("更新数据源： " + source);
        return operationResult.setData(source).setMessage("更新成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }

    /**
     * 查询所有数据源
     *
     * @return 返回查询所有数据源的结果集
     */
    @PostMapping("/mw/api/data/source/query/all")
    public OperationResult<IPage<DataSource>> findDataSourcesByStatus(@RequestParam(name = "pageNum",required = false,defaultValue = "1") Integer pageNum,
                                                                      @RequestParam(name = "pageSize",required = false,defaultValue = "10") Integer pageSize,
                                                                      @RequestBody(required = false) String condition) {
        OperationResult<IPage<DataSource>> result = new OperationResult<>();
        Page<DataSource> page = new Page<>(pageNum, pageSize);
        IPage<DataSource> dataSourceByPage = null;
        try {
            dataSourceByPage = dataSourceService.findDataSourceByPage(page, condition);
        } catch (Exception e) {
            log.info(e.getMessage());
            return result.setMessage("条件查询异常："+e.getMessage()).setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        return result.setData(dataSourceByPage).setMessage("查询成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }
}
