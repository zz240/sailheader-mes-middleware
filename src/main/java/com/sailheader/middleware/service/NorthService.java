package com.sailheader.middleware.service;

import com.huaweicloud.sdk.iot.module.ItClient;
import com.huaweicloud.sdk.iot.module.exception.CryptException;
import com.huaweicloud.sdk.iot.module.exception.HttpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("huawei-cloud")
public class NorthService {
    private ItClient itClient;

    @Autowired
    public NorthService(ItClient itClient) {this.itClient = itClient;}

    public String sendMsg(String url, String msg) throws Exception {
        String result = "";
        try {
            result = itClient.postJson(url, msg);
        } catch (HttpException | CryptException e) {

            throw new Exception("调用云上post请求失败!");
        }
        System.out.println(result);
        return result;
    }
}
