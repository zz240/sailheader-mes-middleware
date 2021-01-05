package com.sailheader.middleware.common.helper;

import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.fastjson.JSONObject;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JsonObjectHelper {
    /**
     * 递归计算对象数量
     * @param jsonData   数据对象
     * @param childKey   递归子节点Key
     */
    public static Long recursionCount(List<JSONObject> jsonData, String childKey){
        return recursionCount(jsonData,childKey,0L);
    }

    private static Long recursionCount(List<JSONObject> jsonData,String childKey,Long count){
        if(jsonData!=null){
            for (int i = 0; i < jsonData.size(); i++) {
                count=count+1;
                JSONObject tempDepartment = jsonData.get(i);
                List<JSONObject> childObject=tempDepartment.getJSONArray(childKey).toJavaList(JSONObject.class);
                count=recursionCount(childObject,childKey,count);
            }
        }

        return count;
    }

    public static JSONObject parseJSONObject(Object obj){
        if(obj==null){
            return null;
        }
        return JSONObject.parseObject(JSONObject.toJSONStringWithDateFormat(obj, "yyyy-MM-dd HH:mm:ss", SerializerFeature.UseSingleQuotes));
    }

    public static String toJSONString(Object obj){
        if(obj==null){
            return null;
        }
        return JSONObject.toJSONStringWithDateFormat(obj, "yyyy-MM-dd HH:mm:ss");
    }



    public static List<JSONObject> parseJSONObjectList(Object obj){
        if(obj==null){
            return null;
        }
        return JSONObject.parseArray(JSONObject.toJSONStringWithDateFormat(obj,"yyyy-MM-dd HH:mm:ss", SerializerFeature.UseSingleQuotes),JSONObject.class);
    }

}
