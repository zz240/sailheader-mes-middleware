package com.sailheader.middleware.action.remote.db.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@Mapper
public interface DynamicDataSourceSqlWithJsonDao {

    int insertAll(String tableName, String fieldStr, List<Map<String, Object>> list);

}
