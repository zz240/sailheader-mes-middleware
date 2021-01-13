package com.sailheader.middleware.service;

import com.sailheader.middleware.mapper.ActionDAO;
import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.ActionExample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActionService {
    @Autowired
    private ActionDAO actionDAO;

    public Action selectByPrimaryKey(Long id){
        return actionDAO.selectByPrimaryKey(id);
    }

    public int insertSelective(Action action){
        return actionDAO.insertSelective(action);
    }

    public int updateByActionStreamSelective(Action action){
        return actionDAO.updateByActionStreamSelective(action);
    }

    public int deleteActionStreamById(Long id){
        return actionDAO.deleteActionStreamById(id);
    }

    public List<Action> findAllActionsByStatus(Short status){
        ActionExample actionExample = new ActionExample();
        actionExample.createCriteria().andStatusNotEqualTo(status);
        return actionDAO.selectByExample(actionExample);
    }

}
