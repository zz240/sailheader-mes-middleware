package com.sailheader.middleware.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.sailheader.middleware.model.Action;

import java.util.List;

/**
 * <p>
 * 动作 Action表 服务类
 * </p>
 *
 * @author Elizer
 * @since 2021-02-19
 */
public interface IActionService extends IService<Action> {
    Action selectByPrimaryKey(Long id);

    void insertAction(Action action);

    int updateAction(Action action);

    int deleteAction(Action action);

    IPage<Action> selectPage(IPage page,Integer jobId,String condition)throws Exception;

    int checkForPresence(String name);

    int checkJobIdPresence(Long jobId);

    void validateDataSource(Action action) throws Exception;

    void checkHttp(JSONObject jsonObject);

    void checkDB(JSONObject jsonObject);

    Object checkActionPass(Action action,String inputData) throws Exception;

}
