package com.sailheader.middleware.action.remote;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.cloud.apigateway.sdk.utils.Client;
import com.cloud.apigateway.sdk.utils.Request;
import com.sailheader.middleware.action.remote.db.service.DynamicDataSourceSqlService;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.constant.ActionStatus;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.*;

/**
 * 远程访问 http roma 动作
 */
@Component("action.remote.access.http.roma")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HttpWithROMAAccess extends AccessAdapter<JSON> {

    @Override
    protected JSON execute(ActionTempData<JSON> actionTempData) throws Exception {
        if (actionTempData == null) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "输入数据为空");
        }
        if (actionTempData.getStatus() != ActionStatus.SUCCESS && actionTempData.getStatus() != ActionStatus.INITIAL) {
            throw MiddlewareException.getInstance(ACTION_STATUS_CONFUSE_ACCESS, "上一个动作需要成功 或者 动作初始化才能继续执行 db 访问动作!");
        }

        //Create a new request.
        Request request = new Request();
        JSONObject addition = dataSource.getAddition();
        try {
            request.setKey(addition.getString("key"));
            request.setSecret(addition.getString("secret"));
            request.setMethod(addition.getString("method"));
            request.setUrl(dataSource.getAddress());

            // 添加 header
            if (addition.getJSONObject("header") != null ) {
                for (Map.Entry<String, Object> header : addition.getJSONObject("header").entrySet()) {
                    request.addHeader(header.getKey(), (String) header.getValue());
                }
            }

            // 添加 query
            if (addition.getJSONObject("query") != null) {
                for (Map.Entry<String, Object> query : addition.getJSONObject("query").entrySet()) {
                    request.addQueryStringParam(query.getKey(), (String) query.getValue());
                }
            }

            // 如果输入数据不存在 则使用 datasource 中的 body
            if (actionTempData.getBody() != null) {
                request.setBody(actionTempData.getBody().toJSONString());
            } else if (addition.get("body") != null) {
                request.setBody(JSON.toJSONString(addition.get("body")));
            }

        } catch (Exception e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_REQUEST_CONSTRUCT_ERROR, e);
        }

        JSON result;
        try {
            //签名请求
            okhttp3.Request signedRequest = Client.signOkhttp(request);

            //发送消息
            OkHttpClient client = new OkHttpClient.Builder().build();
            Response response = client.newCall(signedRequest).execute();

            if (!response.isSuccessful()) {
                throw new IOException("http code: " + response.code() + "; message: " + response.message());
            }
            // 接受并解析请求为json
            ResponseBody body = response.body();
            String str = body != null ? body.string() : "";
            result = str.equals("") ? null : (JSON) JSON.parse(str);

        } catch (IOException e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_REQUEST_SEND_ERROR, e);
        } catch (JSONException e) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_RESPONSE_PARSE_JSON, e);
        } catch (Exception e) {
            e.printStackTrace();
            throw MiddlewareException.getInstance(UNDEFINED_ERROR, e);
        }

        return result;
    }

    @Override
    protected void validateDataSource() throws Exception {
        if (dataSource == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_IS_NULL);
        }

        if (dataSource.getAddition() == null) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_ADDITION_IS_NULL);
        }

        if (dataSource.getAddress() == null || dataSource.getAddress().equals("")) {
            throw MiddlewareException.getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM);
        }

        JSONObject jsonObject = dataSource.getAddition();
        if (jsonObject.get("key") == null || jsonObject.get("key").equals("")) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM, "缺少 key");
        }
        if (jsonObject.get("secret") == null || jsonObject.get("secret").equals("")) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM, "缺少 secret");
        }
        if (jsonObject.get("method") == null || jsonObject.get("method").equals("")) {
            throw MiddlewareException
                    .getInstance(REMOTE_ACCESS_HTTP_WITH_ROMA_DATASOURCE_MISSING_REQUIRE_PARAM, "缺少 method");
        }
    }
}
