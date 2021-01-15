package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.service.DataUpdateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
public class DataUpdateController {
//    @Autowired
//    private ImportJobService importJobService;
//
//    @Autowired
//    private DataUpdateService dataUpdateService;
//
//    /**
//     * 通过名称 执行数据同步（用户请求传参，前端调用传参）
//     * @param name 同步任务的名称
//     * @return
//     */
//    @GetMapping("/api/data/import/execute/{name}")
//    public OperationResult executeDataSync(@PathVariable String name){
//        ImportJob job = importJobService.findImportJobByName(name);
//        try {
//            if (job != null && StringUtils.hasText(job.getRestUrl())) {
//                log.info("启动数据同步,开始同步REST：" + JSONObject.toJSONString(job));
//                return dataUpdateService.executeImportJobClassConfigRestApi(job);
//            }
//        } catch (Exception e) {
////            e.printStackTrace();
//        }
//        return new OperationResult().setData(null).setMessage("启动数据同步失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
//    }

    @RequestMapping("/test")
    public String test(HttpServletRequest request){
        System.out.println(request.getQueryString());
        System.out.println(request.getContextPath());
        System.out.println(request.getMethod());
        return "{'code':'success'}";
    }


}
