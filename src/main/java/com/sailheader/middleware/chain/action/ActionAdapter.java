package com.sailheader.middleware.chain.action;

import com.alibaba.fastjson.JSON;
import com.sailheader.middleware.chain.ActionTempData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 泛型适配器
 * @param <O>
 * @param <I>
 */

public abstract class ActionAdapter<O, I> implements Action {
    @Setter
    @Getter
    protected JSON addition;

    @Override
    public ActionTempData<?> dataHandler(ActionTempData<?> object) throws Exception {

        // 1. 校验输入内容并转换为泛型内容
        ActionTempData<I> sendData;
        try {
            sendData = (ActionTempData<I>) object;
        } catch (Exception e) {
            throw new Exception("数据类型不一致!");
        }

        if (sendData == null) {
            throw new Exception("数据类型不一致!");
        }

        // 2. 校验附加内容
        validateAddition();

        // 3. 执行并范围内容
        return this.execute(sendData);
    }

    // 实际执行需要实现的内容
    protected abstract ActionTempData<O> execute(ActionTempData<I> object);

    // 每个动作必须校验 addition
    protected abstract void validateAddition() throws Exception;

}
