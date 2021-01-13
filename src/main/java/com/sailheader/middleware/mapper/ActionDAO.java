package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.Action;
import org.springframework.stereotype.Repository;

@Repository
public interface ActionDAO {

    Action selectByPrimaryKey(Long id);

    int insertSelective(Action action);

    int updateByActionStreamSelective(Action action);

    int deleteActionStreamById(Long id);

}
