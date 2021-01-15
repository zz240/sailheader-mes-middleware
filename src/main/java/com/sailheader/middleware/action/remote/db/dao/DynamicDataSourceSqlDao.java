package com.sailheader.middleware.action.remote.db.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@Mapper
public interface DynamicDataSourceSqlDao {
    @Select("${sql}")
    @ResultType(Map.class)
    List<Map<String,Object>> executeSql(String sql);
}
