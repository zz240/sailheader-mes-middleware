package com.sailheader.middleware.action.remote.db.service;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.action.remote.db.dao.DynamicDataSourceSqlDao;
import com.sailheader.middleware.action.remote.db.dao.DynamicDataSourceSqlWithJsonDao;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.model.DataSource;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

@Service
public class DynamicDataSourceSqlWithJsonService {

    @Autowired
    private DynamicDataSourceSqlWithJsonDao dataSourceSqlWithJsonDao;

    public int insertAll(String tableName, List<Map<String, Object>> inputData, DataSource dataSource) {
        int count;
        String field = "";
        try {
            count = dataSourceSqlWithJsonDao.insertAll(tableName, field, inputData);
        } catch (MyBatisSystemException e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_CONNECT_FAILURE, e);
        } catch (BadSqlGrammarException e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_SQL_EXECUTE_FAILURE, e);
        } catch (Exception e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_RESULT_CANT_CONVERT_JSON, e);
        }

        if (count == 0) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_RESULT_CANT_CONVERT_JSON, "返回结果为空");
        }
        return count;
    }
}
