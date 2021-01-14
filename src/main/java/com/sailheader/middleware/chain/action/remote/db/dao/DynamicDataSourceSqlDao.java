package com.sailheader.middleware.chain.action.remote.db.dao;

import com.alibaba.fastjson.JSONObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

@Repository
@Mapper
public interface DynamicDataSourceSqlDao {
    @Select("${sql}")
    @ResultType(Map.class)
    Map<String, Object> executeSql(String sql);
}
