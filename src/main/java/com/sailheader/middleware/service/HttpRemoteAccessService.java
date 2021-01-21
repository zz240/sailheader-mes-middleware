package com.sailheader.middleware.service;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.action.ActionHandlerAdapter;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.constant.ActionStatus;
import com.sailheader.middleware.model.Action;
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
import java.util.*;

@Slf4j
@Component("rest.remoteAccess")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HttpRemoteAccessService extends ActionHandlerAdapter<JSON, JSON> {
    @Autowired
    private RestTemplate restTemplate;

    @Override
    protected ActionTempData<JSON> execute(ActionTempData<JSON> object) {
        ResponseEntity<String> exchange = null;
        try {
            RequestEntity requestEntity = getRequestEntity(action);
            exchange = restTemplate.exchange(requestEntity, String.class);
            return new ActionTempData(ActionStatus.SUCCESS,exchange.getBody());
        } catch (URISyntaxException e) {
            e.printStackTrace();
            log.info("远程访问异常！");
        }
        return new ActionTempData(ActionStatus.FAILURE,null);
//        return null;
    }

    @Override
    protected void validateActionAddition() throws Exception {

    }

    //构建请求对象
    public RequestEntity getRequestEntity(Action action) throws URISyntaxException {
        JSONObject prefix = action.getAddition().getJSONObject("addition").getJSONObject("dataSource").getJSONObject("addition");
        String query = prefix.getString("query");
        String url = prefix.get("address") + (query != null ? "?" + query : "");
        HttpMethod requestMethod = HttpMethod.resolve(prefix.getString("method"));

        Map<String, List<String>> headers = new HashMap<>();
        JSON body = null;
        if (!prefix.getString("body").trim().isEmpty()) {
            body = prefix.getJSONObject("body");
        }

        if (prefix.get("header") != null) {
            for (Map.Entry<String, Object> header : prefix.getJSONObject("header").entrySet()) {
                headers.put(header.getKey(), (List<String>)JSONArray.parse(JSONArray.toJSONString(header.getValue())));

            }
        }

        MultiValueMap<String, String> multiValueMap = new MultiValueMapAdapter(headers);
        RequestEntity requestEntity = new RequestEntity<>(body, multiValueMap, requestMethod, new URI(url));
        return requestEntity;
    }
}
