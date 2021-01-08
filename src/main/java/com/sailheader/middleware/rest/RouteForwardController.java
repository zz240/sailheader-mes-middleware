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
import org.springframework.http.RequestEntity;
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

    @RequestMapping(value = "/api/route/enterprise/url/mapping", method = {RequestMethod.GET, RequestMethod.POST})
    public OperationResult createRouteUrl(HttpServletRequest request, @RequestParam(value = "code", required = false) String enterpriseCode) {
        RequestEntity requestEntity = null;
        EnterpriseRouteMapping routeMapping = null;
        HttpMethod requestMethod = HttpMethod.resolve(request.getMethod());
        try {
            if (requestMethod.matches(GET) && enterpriseCode != null) {
                routeMapping = enterpriseRouteMappingService.selectByEnterpriseCode(enterpriseCode);
                log.info("获取到"+routeMapping.getEnterpriseName()+"的GET请求...");
                requestEntity = routeForwardService.routeForwardUrl(request, routeMapping, requestMethod);
                routeForwardService.route(requestEntity);
                log.info("发送成功！");
                return new OperationResult().setMessage("转发成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
            } else {
                JSONObject json = routeForwardService.parsePostBody(request);
                routeMapping = enterpriseRouteMappingService.selectByEnterpriseCode(json.get("code").toString());
                log.info("获取到"+routeMapping.getEnterpriseName()+"的POST请求...");
                requestEntity = routeForwardService.routeRediectUrl(request, routeMapping, requestMethod, json);
                routeForwardService.route(requestEntity);
                log.info("发送成功！");
                return new OperationResult().setMessage("转发成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.info("请求转发失败！");
            return new OperationResult().setMessage("转发失败！").setStateCode(StateCode.SYSTEM_ERROR.getValue());
        }
    }

//    @RequestMapping(value = "/test",method = RequestMethod.GET)
//    public void test(@RequestParam("code") String enterpriseCode, @RequestParam("name") String data) {
//        System.out.println("success ");
//        System.out.println(enterpriseCode + "||" + data);
//    }
//
//    @RequestMapping(value = "/string/test")
//    public void getHttpBodyByStr(HttpServletRequest request) throws Exception {
//        System.out.println("SUCCESS");
//        RequestWrapper requestWrapper = new RequestWrapper(request);
//        System.out.println(requestWrapper.getBody());
//    }
}
