package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.helper.RequestWrapper;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.EnterpriseRouteMapping;
import com.sailheader.middleware.service.EnterpriseRouteMappingService;
import com.sailheader.middleware.service.RouteForwardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;


@Slf4j
@RestController
public class RouteForwardController {
    private final String GET = "GET";
    @Autowired
    private RouteForwardService routeForwardService;
    @Autowired
    private EnterpriseRouteMappingService enterpriseRouteMappingService;

    @RequestMapping(value = "/mes/{enterpriseCode}/api/route/enterprise/url", method = {RequestMethod.GET, RequestMethod.POST})
    public OperationResult createRouteUrl(HttpServletRequest request, @PathVariable("enterpriseCode") String enterpriseCode) {
        RequestEntity requestEntity = null;
        HttpMethod requestMethod = HttpMethod.resolve(request.getMethod());
        EnterpriseRouteMapping routeMapping = enterpriseRouteMappingService.selectByEnterpriseCode(enterpriseCode);
        if (routeMapping != null) try {
            if (requestMethod.matches(GET)) {
                log.info("获取到" + routeMapping.getEnterpriseName() + "的GET请求...");
                requestEntity = routeForwardService.routeForwardUrl(request, routeMapping, requestMethod);
            } else {
                log.info("获取到" + routeMapping.getEnterpriseName() + "的POST请求...");
                requestEntity = routeForwardService.routeRediectUrl(request, routeMapping, requestMethod);
            }
            ResponseEntity<String> route = routeForwardService.route(requestEntity);
            if (route.getStatusCode().equals(HttpStatus.OK)) {
                log.info("发送成功！");
                return new OperationResult().setMessage("转发成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
            }
        } catch (Exception e) {
            log.info("请求转发失败！");
            return new OperationResult().setMessage("转发异常！").setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
        return new OperationResult().setMessage("转发失败！请检查您的企业编号！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

//    @RequestMapping(value = "/test",method = RequestMethod.GET)
//    public void test(HttpServletRequest request) {
//        System.out.println("success ");
//    }
//
//    @PostMapping(value = "/string/test")
//    public void getHttpBodyByStr(HttpServletRequest request){
//        System.out.println("SUCCESS");
//    }
}