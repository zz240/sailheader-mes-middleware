package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.service.IDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * saas数据同步接口
 */
@RestController
@RequestMapping("/mw/api/data")
public class DataController {

    @Autowired
    IDataService iDataService;

    /**
     * 全局数据同步
     *
     * @return
     */
    @PostMapping("/import")
    public OperationResult<?> dataImport(@RequestBody Map<String, ?> importData) {
        OperationResult<?> result = new OperationResult<>();
        try {
            result = iDataService.importData(importData);
        } catch (Exception e) {
            result.setMessage(e.getMessage()).setStateCode(StateCode.SYSTEM_ERROR.getEnUSLabel());
        }
        return result;
    }
}
