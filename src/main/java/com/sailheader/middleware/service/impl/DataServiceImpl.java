package com.sailheader.middleware.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.*;
import com.sailheader.middleware.mapper.DataMapper;
import com.sailheader.middleware.service.IDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * saas数据导入 服务实现类
 * </p>
 *
 * @author Steven
 * @since 2021-03-05
 */
@Service
public class DataServiceImpl implements IDataService {
    @Autowired
    private DataMapper dataMapper;

    @Override
    public OperationResult<?> importData(JSONObject importData) throws Exception {
        importData.forEach((tableName, value) -> {
            // 1. 清空数据库
            dataMapper.deleteByTableName(tableName);

            // 2. 构造需要的字段
            List<String> fieldList = parseField(dataMapper.selectFieldByTableName(tableName));
            // 3. 导入数据
            HashMap<String, Object> map = new HashMap<>();
            map.put("tableName", tableName);
            map.put("field", fieldList);
            map.put("inputData", filterField(fieldList, (JSONArray) value));
            dataMapper.insertAll(map);
        });
        return new OperationResult<>(null, StateCode.SYSTEM_SUCCESS.getEnUSLabel());
    }

    /**
     * 获取数据库中存在的字段
     */
    private List<String> parseField(List<Map<String, String>> list) {
        return list.stream().map(item -> item.get("columnname")).sorted().collect(Collectors.toList());
    }

    /**
     * 过滤字段，仅仅需要指定字段的值
     *
     * @return
     */
    private List<Map<String, Object>> filterField(List<String> field, JSONArray data) {
        return data.stream().map(item -> {
            Map<?,?> finalItem = (Map<?, ?>) item;
            // 1. 遍历接口传递的每一行数据
            TreeMap<String, Object> temp = new TreeMap<>();

            // 2. 遍历数据库表字段,与当前map对齐
            field.forEach(fieldItem -> {
                if (finalItem.containsKey(fieldItem)) {
                    Object value = finalItem.get(fieldItem);
                    temp.put(fieldItem, value);
                } else {
                    temp.put(fieldItem, null);
                }
            });
            return temp;
        }).collect(Collectors.toList());
    }
}
