package com.sailheader.middleware.service;

import com.sailheader.middleware.mapper.ActionDAO;
import com.sailheader.middleware.model.Action;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

}
