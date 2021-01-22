package com.sailheader.middleware.action.remote.db.service;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.action.remote.db.dao.DynamicDataSourceSqlDao;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.model.DataSource;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

@Service
public class DynamicDataSourceSqlService {

    @Autowired
    private DynamicDataSourceSqlDao dynamicDataSourceSqlDao;

    public JSON executeSql(String sql, DataSource dataSource) {
        JSON json;

        try {
            json = (JSON) JSON.toJSON(dynamicDataSourceSqlDao.executeSql(sql));
        } catch (MyBatisSystemException e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_CONNECT_FAILURE, e);
        } catch (BadSqlGrammarException e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_SQL_EXECUTE_FAILURE, e);
        } catch (Exception e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_RESULT_CANT_CONVERT_JSON, e);
        }

        if (json == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_DB_RESULT_CANT_CONVERT_JSON, "返回结果为空");
        }
        return json;
    }
}
