package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.Action;
import com.sailheader.middleware.model.ActionExample;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActionDAO {

    Action selectByPrimaryKey(Long id);

    int insertSelective(Action action);

    int updateByActionStreamSelective(Action action);

    int deleteActionStreamById(Long id);

    List<Action> selectByExample(ActionExample actionExample);

    int checkForPresence(String name);

    int updateByActionStream(Action action);

}
