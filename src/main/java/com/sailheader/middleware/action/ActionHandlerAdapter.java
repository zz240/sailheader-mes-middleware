package com.sailheader.middleware.action;

import com.sailheader.middleware.action.consume.ConsumeData;
import com.sailheader.middleware.action.consume.DirectConsume;
import com.sailheader.middleware.chain.ActionTempData;
import com.sailheader.middleware.common.MiddlewareException;
import com.sailheader.middleware.model.Action;
import lombok.Getter;
import lombok.Setter;

import static com.sailheader.middleware.constant.MiddlewareExceptionMap.ACTION_HANDLER_ADAPTER_ACTION_IS_NULL;
import static com.sailheader.middleware.constant.MiddlewareExceptionMap.ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM;

/**
 * 泛型适配器
 *
 * @param <O>
 * @param <I>
 */

public abstract class ActionHandlerAdapter<O, I> implements ActionHandler {
    @Setter
    @Getter
    protected Action action;

    @Setter
    @Getter
    protected ConsumeData consumeData = new DirectConsume();

    @Setter
    @Getter
    protected ActionTempData<I> sendData;

    @Override
    public ActionTempData<O> dataHandler(ActionTempData<?> object) throws Exception {
        // 0. 验证 action 参数有效性
        simpleValidateActionData();

        /**
         * 1. 校验输入内容并转换为泛型内容(? 可以是任何类型转为 I,
         * 例如 ActionTempData<String> => ActionTempData<Integer> )
         * TODO
         */
        sendData = (ActionTempData<I>) object;
        I inputData = sendData != null && sendData.getBody() != null ? sendData.getBody() : null;

        // 2. 根据消费策略调整当前动作的数据
        consumeData.consume(this, inputData);

        // 3. 校验 action addition 的内容
        validateActionAddition();

        // 4. 执行并返回内容
        return this.execute(sendData);
    }

    // 实际执行需要实现的内容
    protected abstract ActionTempData<O> execute(ActionTempData<I> object) throws Exception;

    // 每个动作处理器必须验证 动作 数据
    protected abstract void validateActionAddition() throws Exception;

    /**
     * 验证 action data 参数是否完整
     */
    private void simpleValidateActionData() throws Exception {
        if (action == null) {
            throw MiddlewareException.getInstance(ACTION_HANDLER_ADAPTER_ACTION_IS_NULL);
        }
        if (action.getJobId() == null || action.getJobId() == 0
                || action.getName() == null || action.getName().length() == 0
                || action.getType() == null || action.getType() == 0
                || action.getIndex() == 0) {
            throw MiddlewareException
                    .getInstance(ACTION_HANDLER_ADAPTER_ACTION_MISSING_REQUIRE_PARAM);
        }
    }
}
