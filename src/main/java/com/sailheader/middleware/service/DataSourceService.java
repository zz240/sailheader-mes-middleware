package com.sailheader.middleware.service;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.mapper.DataSourceDAO;
import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.model.DataSourceExample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DataSourceService {
    @Autowired
    private DataSourceDAO dataSourceDAO;

    public int createDataSource(DataSource dataSource){
        int i = dataSourceDAO.insertSelective(dataSource);
        return i;
    }

    public int deleteDataSourceByid(Long id){
        return dataSourceDAO.deleteDataSourceByid(id);
    }

    public DataSource findDataSourceByid(Long id){
        return dataSourceDAO.selectByPrimaryKey(id);
    }

    public int updateDataSource(DataSource dataSource){
        return dataSourceDAO.updateByPrimaryKey(dataSource);
    }

    public List<DataSource> findJobByStatus(Short status){
        DataSourceExample dataSourceExample = new DataSourceExample();
        dataSourceExample.createCriteria().andStatusNotEqualTo(status);
        return dataSourceDAO.selectByExample(dataSourceExample);
    }

}
