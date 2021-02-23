package com.sailheader.middleware.common.helper;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sailheader.middleware.common.vo.ConditionVo;

import java.util.List;

public class SearchUtil {
    public static QueryWrapper parseWhereSql(String conditionJson) throws Exception{
        QueryWrapper queryWrapper = new QueryWrapper();
        if(conditionJson != null){
            List<ConditionVo> conditionList = conditionList = JSON.parseArray(conditionJson,ConditionVo.class);
            if(conditionList.size() > 0 ){
                for(ConditionVo conditionVo : conditionList){
                    switch (conditionVo.getConditionalOperators()){
                        case "EQUALS": queryWrapper.eq(conditionVo.getName(),conditionVo.getValue());break;       //等于
                        case "NOTEQUEALS": queryWrapper.ne(conditionVo.getName(),conditionVo.getValue());break;       //不等于
                        case "CONTAINSTEXT": queryWrapper.like(conditionVo.getName(),conditionVo.getValue());break;   //模糊查询
                        case "NOTCONTAINSTEXT": queryWrapper.notLike(conditionVo.getName(),conditionVo.getValue());break;     //不等于模糊查询
                        case "gt": queryWrapper.gt(conditionVo.getName(),conditionVo.getValue());break;       //大于
                        case "lt": queryWrapper.lt(conditionVo.getName(),conditionVo.getValue());break;       //小于
                    }
                }
            }
        }
        return queryWrapper;
    }
}
