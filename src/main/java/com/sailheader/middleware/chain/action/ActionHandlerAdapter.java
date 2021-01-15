package com.sailheader.middleware.chain.action;

import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.model.Action;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;

/**
 * 泛型适配器
 * @param <O>
 * @param <I>
 */

public abstract class ActionHandlerAdapter<O, I> implements ActionHandler {
    @Setter
    @Getter
    protected Action action;

    @Override
    public ActionTempData<O> dataHandler(ActionTempData<?> object) throws Exception {

        // 1. 校验输入内容并转换为泛型内容
        ActionTempData<I> sendData;
        try {
            sendData = (ActionTempData<I>) object;
        } catch (Exception e) {
            throw new Exception("数据类型不一致!");
        }

//        if (sendData == null) {
//            throw new Exception("数据类型不一致!");
//        }

        // 2. 校验附加内容
        validateActionData();

        // 3. 执行并返回内容
        return this.execute(sendData);
    }

    // 实际执行需要实现的内容
    protected abstract ActionTempData<O> execute (ActionTempData<I> object) throws Exception;

    // 每个动作处理器必须验证 动作 数据
    protected abstract void validateActionData() throws Exception;

}
