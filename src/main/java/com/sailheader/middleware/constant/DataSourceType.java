package com.sailheader.middleware.constant;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public enum DataSourceType {
    HTTP(1, "action.remote.access.http"),
    DB(2, "action.remote.access.db"),
    DB_JSON(3,"action.remote.access.db.json"),
    NOT_FOUND_TEST(100, "action.remote.not.found.test");

    @Getter
    int id;

    @Getter
    String beanName;


    DataSourceType(int id, String beanName) {
        this.id = id;
        this.beanName = beanName;
    }

    public static final Map<Integer,DataSourceType> dataSourceMapById = getEnumById();

    private static Map<Integer, DataSourceType> getEnumById() {
        Map<Integer,DataSourceType> result = new HashMap<>();
        for (DataSourceType value : DataSourceType.values()) {
            result.put(value.id, value);
        }
        return result;
    }
}
