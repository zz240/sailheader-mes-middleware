package com.sailheader.middleware.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sailheader.middleware.model.DataSource;

/**
 * <p>
 * 动作流表 Mapper 接口
 * </p>
 *
 * @author Elizer
 * @since 2021-02-19
 */
public interface DataSourceMapper extends BaseMapper<DataSource> {
    int insertSelective(DataSource dataSource);

    int updateDataSource(DataSource dataSource);

    int updateDataSourceSelective(DataSource dataSource);

}
