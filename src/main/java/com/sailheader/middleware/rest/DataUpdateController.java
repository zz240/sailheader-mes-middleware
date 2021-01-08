package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.AddressRestMapping;
import com.sailheader.middleware.service.AddressRestMappingService;
import com.sailheader.middleware.service.DataUpdateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class DataUpdateController {
    @Autowired
    private AddressRestMappingService addressRestMappingService;

    @Autowired
    private DataUpdateService dataUpdateService;

    /**
     * 通过id执行数据同步,手动执行同步
     * @param id 同步任务id
     * @return
     */
    @GetMapping("/api/data/import/execute/{id}")
    public OperationResult executeDataSync(@PathVariable Long id){
        AddressRestMapping addressRestMapping = addressRestMappingService.findAddressRestMappingById(id);
        try {
            if (addressRestMapping != null && StringUtils.hasText(addressRestMapping.getRestUrl())) {
                log.info("开始同步REST：" + JSONObject.toJSONString(addressRestMapping));
                return dataUpdateService.executeImportJobClassConfigRestApi(addressRestMapping);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new OperationResult().setData(null).setMessage("启动数据同步失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    /**
     * 开启同步任务
     * @return
     */
    public OperationResult startSyncJob(){

        return new OperationResult().setData(null).setMessage("开启同步任务失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }


//    /**
//     * 测试
//     * @param jsonParam
//     */
//    @PostMapping("/test")
//    public void test(@RequestBody JSONArray jsonParam){
//        System.out.println("接收到数据： :"+jsonParam);
//    }
}
