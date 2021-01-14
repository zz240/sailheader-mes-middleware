package com.sailheader.middleware.chain.action.remote.db.type;

import com.alibaba.fastjson.JSON;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedJdbcTypes({JdbcType.VARCHAR})
@MappedTypes({JSON.class})
public class JdbcJsonHandler extends BaseTypeHandler<JSON> {
    @Override
    public void setNonNullParameter(PreparedStatement preparedStatement, int i, JSON json,
                                    JdbcType jdbcType) throws SQLException {

    }

    @Override
    public JSON getNullableResult(ResultSet resultSet, String s) throws SQLException {
        return null;
    }

    @Override
    public JSON getNullableResult(ResultSet resultSet, int i) throws SQLException {
        return null;
    }

    @Override
    public JSON getNullableResult(CallableStatement callableStatement, int i) throws SQLException {
        return null;
    }
}
