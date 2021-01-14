package com.sailheader.middleware.common.helper;

import com.alibaba.fastjson.JSON;

public class FastJsonParseHelper {


    public static JSON parseJson(String json) throws Exception {
        if (json == null) {
            return null;
        }
        JSON result;
        try {
            result = (JSON) JSON.parse(json);
        } catch (Exception e) {
            return null;
        }

        return result;

    }

}
