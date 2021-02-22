package com.sailheader.middleware.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sailheader.middleware.common.helper.SearchUtil;
import com.sailheader.middleware.constant.GeneralStatus;
import com.sailheader.middleware.mapper.DataSourceMapper;
import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.service.IDataSourceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * <p>
 * 数据源表 服务实现类
 * </p>
 *
 * @author Elizer
 * @since 2021-02-19
 */
@Service
public class DataSourceServiceImpl extends ServiceImpl<DataSourceMapper, DataSource> implements IDataSourceService {
    @Autowired
    private DataSourceMapper dataSourceMapper;

    QueryWrapper<DataSource> queryWrapper = new QueryWrapper();

    @Override
    public int createDataSource(DataSource dataSource) {
        return dataSourceMapper.insertSelective(dataSource);
    }

    @Override
    public int deleteDataSourceById(DataSource dataSource) {
        dataSource.setStatus(GeneralStatus.DELETED.getIntLabel())
                .setName(dataSource.getName() + "-" + dataSource.getId());
        dataSource.setModifiedOn(new Date());
        return dataSourceMapper.updateDataSourceSelective(dataSource);
    }

    @Override
    public DataSource findDataSourceByid(Long id) {
        return dataSourceMapper.selectById(id);
    }

    @Override
    public int updateDataSource(DataSource dataSource) {
        return dataSourceMapper.updateDataSource(dataSource);
    }

    @Override
    public IPage<DataSource> findDataSourceByPage(IPage<DataSource> page,String condition) throws Exception {
        QueryWrapper<DataSource> queryWrapper = SearchUtil.parseWhereSql(condition);
        queryWrapper.ne("status",-1);
        return dataSourceMapper.selectPage(page,queryWrapper);
    }

    @Override
    public int checkForPresence(DataSource dataSource) {
        queryWrapper.eq("name",dataSource.getName());
        return dataSourceMapper.selectCount(queryWrapper);
    }
}
