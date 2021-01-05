package com.sailheader.middleware.service;


import com.sailheader.middleware.mapper.ClassConfigDAO;
import com.sailheader.middleware.model.ClassConfig;
import com.sailheader.middleware.model.dto.ClassConfigDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ClassConfigService {

    @Autowired
    private ClassConfigDAO classConfigDAO;

    public ClassConfig findClassConfigByCLassConfigId(Long classConfigId) {
        return classConfigDAO.selectByPrimaryKey(classConfigId);
    }

}
