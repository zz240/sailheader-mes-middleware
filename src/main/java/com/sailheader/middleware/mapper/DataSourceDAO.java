package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.DataSource;
import com.sailheader.middleware.model.DataSourceExample;
import org.springframework.stereotype.Repository;

import javax.xml.crypto.Data;
import java.util.List;

@Repository
public interface DataSourceDAO {

    /**
     * 插入数据源
     * @param dataSource
     * @return
     */
    int insertSelective(DataSource dataSource);

    /**
     * 根据 id 查询数据源
     * @param id    数据源id
     * @return
     */
    DataSource selectByPrimaryKey(Long id);

    /**
     * 根据 数据源名称 查询对应记录
     * @param name  数据源名称
     * @return
     */
    DataSource selectByName(String name);


    /**
     * 根据 id 删除数据源
     * @param id    数据源id
     * @return
     */
    int deleteDataSourceByid(Long id);


    /**
     * 更新数据源
     * @param dataSource
     * @return
     */
    int updateByPrimaryKey(DataSource dataSource);


    List<DataSource> selectByExample(DataSourceExample dataSourceExample);

}
