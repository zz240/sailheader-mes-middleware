package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.ClassConfig;
import org.springframework.stereotype.Repository;

@Repository
public interface ClassConfigDAO {

    ClassConfig selectByPrimaryKey(Long id);
}
