package com.sailheader.middleware.service;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.helper.RequestWrapper;
import com.sailheader.middleware.model.EnterpriseRouteMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

@Service
public class RouteForwardService {
    @Autowired
    private RestTemplate restTemplate;


    /**
     * 路由转发
     * @param requestEntity
     * @return
     */
    public ResponseEntity<String> route(RequestEntity requestEntity) {
        ResponseEntity<String> exchange = restTemplate.exchange(requestEntity, String.class);
        return exchange;
    }

    /**
     * GET
     * @param request 用户请求
     * @param routeMapping  路由映射对象
     * @return
     */
    public RequestEntity routeForwardUrl(HttpServletRequest request, EnterpriseRouteMapping routeMapping,HttpMethod requestMethod) throws URISyntaxException {
        String queryString = request.getQueryString();
        String url = routeMapping.getTargetAddress()+(queryString != null ? "?" + queryString : "");
        MultiValueMap<String, String> headers = parseRequestHeader(request);
        RequestEntity requestEntity = new RequestEntity<>(headers, requestMethod, new URI(url));
        return requestEntity;
    }

    /**
     * POST
     * @param request   用户请求
     * @param routeMapping  路由映射对象
     * @return
     */
    public RequestEntity routeRediectUrl(HttpServletRequest request,EnterpriseRouteMapping routeMapping,HttpMethod requestMethod) throws IOException, URISyntaxException {
        JSONObject body = parsePostBody(request);
        String url = routeMapping.getTargetAddress();
        MultiValueMap<String, String> headers = parseRequestHeader(request);
        RequestEntity requestEntity = new RequestEntity<>(body, headers, requestMethod, new URI(url));
        return requestEntity;
    }

    private MultiValueMap<String, String> parseRequestHeader(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        List<String> headerNames = Collections.list(request.getHeaderNames());
        for (String headerName : headerNames) {
            List<String> headerValues = Collections.list(request.getHeaders(headerName));
            for (String headerValue : headerValues) {
                headers.add(headerName, headerValue);
            }
        }
        return headers;
    }

    // 解析请求体
    public JSONObject parsePostBody(HttpServletRequest request) throws IOException {
        RequestWrapper requestWrapper = new RequestWrapper(request);
        String body = requestWrapper.getBody();
        JSONObject json = JSON.parseObject(body);
        return json;
    }

}
