package com.sailheader.middleware.rest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.common.helper.TaskUtils;
import com.sailheader.middleware.model.Job;
import com.sailheader.middleware.service.JobService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.FIELD_MAPPING_DATA_IS_NULL;


@RestController
public class TestController {
    JSONObject json = new JSONObject();
    @Autowired
    private JobService jobService;

    // test
    @RequestMapping("/test")
    public JSON test(HttpServletRequest request) {
        System.out.println("SUCCESS :" + new Date().getTime());
        json.put("data", "43623414175e9867890");
        return json;
    }


    // 转发
    @RequestMapping("/test02")
    public JSON test02(HttpServletRequest  request){
        json.put("username","admin");
        json.put("password","admin1234");
        return json;
    }

    @RequestMapping("/testGetData")
    public JSON testFieldMapping(){
        JSONArray arr = JSONArray.parseArray("[{\"tid\":1,\"tname\":\"篮球\",\"miaoshu\":\"ball\"},{\"miaoshu\":\"ball\"},{\"tid\":\"3\",\"tname\":\"steam\",\"desc\":\"game\"}]");
        JSONObject object = new JSONObject();
        object.put("tid",(long)2);
        object.put("tname",new Date());
        object.put("flag",false);
        object.put("miaoshu","test");
        arr.add(object);
        System.out.println("原始数据："+arr);
        return arr;
    }

    @RequestMapping("/testReceiveData")
    public void testReceiveData(JSON json){
        System.out.println("------接收数据-----");
        System.out.println(json);

    }


    // 接收
    @RequestMapping("/test03")
    public JSON test03(@RequestBody test test) throws IOException {
        System.out.println("======   SUCCESS   =======");
        System.out.println(test);
        return (JSON) JSON.parse(JSON.toJSONString(test));
    }

    @RequestMapping("/find/active/timed/task")
    public void test05(){
        List<Job> activeTimedTask = jobService.findActiveTimedTask();
        if (activeTimedTask.size() == 0){
            System.out.println("暂无定时任务");
        }else{
            activeTimedTask.forEach((job)->{
                System.out.println(job);
            });
        }
    }

    @Data
    @AllArgsConstructor
    static class test {
        private String password;
        private String username;
    }


    public static void main(String[] args) throws ParseException {
        Object jsonString = new JSONObject();
        if (jsonString instanceof List){
            System.out.println("Array");
        }
        if (jsonString instanceof Map){
            System.out.println("Object");
        }

    }


}
