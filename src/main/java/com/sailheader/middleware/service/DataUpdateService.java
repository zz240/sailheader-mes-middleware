package com.sailheader.middleware.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.helper.JsonObjectHelper;
import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.model.ClassConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.springframework.http.HttpHeaders.ACCEPT_ENCODING;

@Slf4j
@Service
public class DataExportService {
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private OkHttpClient okHttpClient;

    private final static String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    public final static String UTF_8 = "utf-8";

    /**
     * 执行同步REST
     * @param classConfig
     * @return
     * @throws Exception
     */
    public OperationResult executeImportJobClassConfigRestApi(ClassConfig classConfig) throws Exception {
        OperationResult operationResult = new OperationResult();
        if (classConfig != null && StringUtils.hasText(classConfig.getRestUrl())) {
            Response response = requestConnection(classConfig.getRestUrl());
            if (response.isSuccessful()) {
//                log.info("开始获取body内容！");
                String result = response.body().string();
                response.close();
                // 转换成json
                JSONObject data = (JSONObject) JSONObject.parse(result);
                String url = "http://localhost:3360/test";
                log.info("开始同步REST：" + url);
                updateByClassConfigRestApi(url,data);
                return operationResult.setData(null).setMessage("启动" + classConfig.getZhCnName() + "成功！").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
            }
            else {
                throw new IOException("Unexpected code " + response);
            }
        }
        return operationResult.setData(null).setMessage("启动数据同步失败！").setStateCode(StateCode.SYSTEM_FAIL.getValue());
    }

    /**
     * 建立REST访问请求源地址
     * @param url   源地址
     * @return      响应对象
     * @throws IOException
     */
    private Response requestConnection(String url) throws IOException {
        url = "http://www.kuaidi100.com/query?type=zhongtong&postid=75427719786230";
        log.info("请求REST地址："+url+"");
        final Request request = new Request.Builder()
                .url(url)
                .addHeader(HttpHeaders.CONTENT_TYPE, JSON_CONTENT_TYPE)
                .addHeader(HttpHeaders.ACCEPT_CHARSET, UTF_8)
                .addHeader(ACCEPT_ENCODING, "identity")
                .get().build();
        return okHttpClient.newCall(request).execute();
    }

    /**
     * POST访问目标地址
     * @param url
     * @param data
     */
    public void updateByClassConfigRestApi(String url,JSONObject data){
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE,JSON_CONTENT_TYPE);
        headers.add(HttpHeaders.ACCEPT_CHARSET, UTF_8);
        headers.add(ACCEPT_ENCODING, "identity");
        HttpEntity entity = new HttpEntity<>(data, headers);
        restTemplate.postForObject(url, entity, OperationResult.class);
    }

}
