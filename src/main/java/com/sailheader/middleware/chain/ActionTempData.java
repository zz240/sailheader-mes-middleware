package com.sailheader.middleware.chain;

import com.sailheader.middleware.constant.ActionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 每一动作产生的中间状态数据集
 * @param <T>
 */

@Data
@AllArgsConstructor
@Accessors(chain = true)
public class ActionTempData<T> {
    private ActionStatus status;
    private T body;

}
