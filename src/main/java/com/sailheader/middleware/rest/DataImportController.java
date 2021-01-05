package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.ClassConfig;
import com.sailheader.middleware.service.ClassConfigService;
import com.sailheader.middleware.service.DataExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class DataImportController {

    @Autowired
    private ClassConfigService classConfigService;

    @Autowired
    private DataExportService dataExportService;

    /**
     * 通过id执行数据同步,手动执行同步
     * @param id 同步任务id
     * @return
     */
    @GetMapping("/api/data/import/execute/{id}")
    public OperationResult executeDataImport(@PathVariable Long id){
        ClassConfig classConfig = classConfigService.findClassConfigByCLassConfigId(id);
            try {
                if (classConfig != null && StringUtils.hasText(classConfig.getRestUrl())) {
                    log.info("开始同步REST：" + JSONObject.toJSONString(classConfig));
                    return dataExportService.executeImportJobClassConfigRestApi(classConfig);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        return new OperationResult().setData(null).setMessage("启动数据同步失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    @PostMapping("/test")
    public void test(@RequestBody JSONObject jsonParam){
        System.out.println("test :"+jsonParam);
    }
}
