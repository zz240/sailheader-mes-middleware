package com.sailheader.middleware.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.sailheader.middleware.model.DataSource;

import javax.xml.crypto.Data;
import java.util.List;

/**
 * <p>
 * 动作流表 服务类
 * </p>
 *
 * @author Elizer
 * @since 2021-02-19
 */
public interface IDataSourceService extends IService<DataSource> {
    int createDataSource(DataSource dataSource);

    int deleteDataSourceById(DataSource dataSource);

    DataSource findDataSourceByid(Long id);

    int updateDataSource(DataSource dataSource);

    IPage<DataSource> findDataSourceByPage(IPage<DataSource> page,String condition)throws Exception;

    int checkForPresence(DataSource dataSource);
}
