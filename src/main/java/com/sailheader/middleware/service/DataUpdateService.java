package com.sailheader.middleware.service;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.springframework.http.HttpHeaders.ACCEPT_ENCODING;

@Slf4j
@Service
public class DataUpdateService {
    private final static String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    public final static String UTF_8 = "utf-8";


     //执行同步REST
//    public OperationResult executeImportJobClassConfigRestApi(ImportJob importJob) throws Exception {
//        OperationResult operationResult = new OperationResult();
//        if (importJob != null && StringUtils.hasText(importJob.getRestUrl()) && StringUtils.hasText(importJob.getTargetAddress())) {
//            Response response = requestConnection(importJob.getRestUrl());
//            if (response.isSuccessful()) {
//                String result = response.body().string();
//                response.close();
//                JSONObject data = (JSONObject) JSONObject.parse(result);
//                // 目标地址
//                String url = importJob.getTargetAddress();
//                log.info("开始同步REST：" + url);
//                updateByClassConfigRestApi(url,data);
//                return operationResult.setData(data).setMessage("启动" + importJob.getName() + "成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
//            }
//            else {
//                throw new IOException("Unexpected code " + response);
//            }
//        }
//        return operationResult.setData(null).setMessage("启动数据同步失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
//    }
//
//    /**
//     * 建立http访问请求源地址
//     * @param url   源地址
//     * @return      响应对象
//     * @throws IOException
//     */
//    private Response requestConnection(String url) throws IOException {
//        log.info("请求REST地址："+url+"");
//        final Request request = new Request.Builder()
//                .url(url)
//                .addHeader(HttpHeaders.CONTENT_TYPE, JSON_CONTENT_TYPE)
//                .addHeader(HttpHeaders.ACCEPT_CHARSET, UTF_8)
//                .addHeader(ACCEPT_ENCODING, "identity")
//                .method(HttpMethod.GET.toString(),null).build();
//        return new OkHttpClient().newCall(request).execute();
//    }

    /**
     * POST访问目标地址
     * @param url
     * @param data
     */
    public void updateByClassConfigRestApi(String url,JSONObject data){
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE,JSON_CONTENT_TYPE);
        headers.add(HttpHeaders.ACCEPT_CHARSET, UTF_8);
        headers.add(ACCEPT_ENCODING, "identity");
        HttpEntity entity = new HttpEntity<>(data, headers);
        restTemplate.postForObject(url, entity, OperationResult.class);
    }

}
