package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.constant.MiddlewareExceptionMap;
import com.sailheader.middleware.model.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.MultiValueMapAdapter;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

/**
 * 远程访问 http 动作
 */
@Slf4j
@Component("action.remote.access.http")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HttpAccessAdapter extends AccessAdapter<JSON> {
    @Autowired
    private RestTemplate restTemplate;

    @Override
    protected JSON execute(ActionTempData<JSON> actionTempData) throws Exception {
        ResponseEntity<String> response = null;
        RequestEntity requestEntity = null;
        if (actionTempData.getStatus() != ActionStatus.SUCCESS && actionTempData.getStatus() != ActionStatus.INITIAL) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "上一个动作需要成功 或者 动作初始化才能继续执行 http 访问动作!");
        }
        JSON lastBody = actionTempData.getBody();

        //构建访问对象
        try {
            requestEntity = getRequestEntity(dataSource,lastBody);
        } catch (URISyntaxException e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_BUILD_FAILURE,e);
        }

        //http访问
        try {
            response = restTemplate.exchange(requestEntity, String.class);
        }catch (Exception e){
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_ACCESS_ERROR,e);
        }

        return JSONObject.parseObject(response.getBody());
    }

    @Override
    protected void validateDataSource() throws Exception {
        if (dataSource == null){
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.REMOTE_ACCESS_HTTP_DATASOURCE_IS_NULL);
        }
        if (dataSource.getAddition() == null){
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.REMOTE_ACCESS_HTTP_DATASOURCE_ADDITION_IS_NULL);
        }
        if (dataSource.getAddress() == null){
            throw MiddlewareException.getInstance(MiddlewareExceptionMap.REMOTE_ACCESS_HTTP_DATASOURCE_ADDRESS_IS_NULL);
        }
        JSONObject jsonObject = dataSource.getAddition();
        if (jsonObject.getString("method") == null || jsonObject.getString("method").trim().equals("")){
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_MISSING_REQUIRE_PARAM,"缺少 method");
        }
    }

    //构建请求对象
    public RequestEntity getRequestEntity(DataSource dataSource,JSON json) throws URISyntaxException {
        JSONObject addition = dataSource.getAddition();
        String query = addition.getString("query");
        String url = dataSource.getAddress() + (query != null ? "?" + query : "");
        HttpMethod requestMethod = HttpMethod.resolve(addition.getString("method"));
        Map<String, List<String>> headers = new HashMap<>();
        JSON body = null;

        if (json != null){
            body = json;
        }

        if (addition.get("header") != null) {
            for (Map.Entry<String, Object> header : addition.getJSONObject("header").entrySet()) {
                headers.put(header.getKey(), (List<String>) JSONArray.parse(JSONArray.toJSONString(header.getValue())));
            }
        }
        MultiValueMap<String, String> multiValueMap = new MultiValueMapAdapter(headers);
        RequestEntity requestEntity = new RequestEntity<>(body, multiValueMap, requestMethod, new URI(url));
        return requestEntity;
    }

}
