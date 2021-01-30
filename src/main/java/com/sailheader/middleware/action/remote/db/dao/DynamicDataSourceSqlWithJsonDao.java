package com.sailheader.middleware.action.remote.db.dao;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
@Mapper
public interface DynamicDataSourceSqlWithJsonDao {

    int insertAll(Map map);


}
