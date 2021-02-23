package com.sailheader.middleware.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sailheader.middleware.model.Action;

/**
 * <p>
 * 动作流表 Mapper 接口
 * </p>
 *
 * @author Elizer
 * @since 2021-02-19
 */
public interface ActionMapper extends BaseMapper<Action> {
    void insertAction(Action action);

    int updateAction(Action action);

    int checkJobIdPresence(Long jobId);

    int updateByActionSelective(Action action);
}
