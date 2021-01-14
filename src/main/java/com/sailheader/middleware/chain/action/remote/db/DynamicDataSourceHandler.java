package com.sailheader.middleware.chain.action.remote.db;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;

public class DynamicDataSourceHandler extends AbstractRoutingDataSource {

    private static final ThreadLocal<DataSource> DATASOURCE = new ThreadLocal<>();

    @Override
    protected Object determineCurrentLookupKey() {
        return null;
    }

    @Override
    protected DataSource determineTargetDataSource() {
        return getDataSource();
    }

    public static DataSource getDataSource() {
        return DynamicDataSourceHandler.DATASOURCE.get();
    }

    public static void setDataSource(DataSource dataSource) {
        DynamicDataSourceHandler.DATASOURCE.set(dataSource);
    }

    public static void clear() {
        DynamicDataSourceHandler.DATASOURCE.remove();
    }
}
