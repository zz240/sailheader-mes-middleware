package com.sailheader.middleware.chain;

import com.sailheader.middleware.constant.ActionStatusEnum;
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
    private ActionStatusEnum status;
    private T body;

}
