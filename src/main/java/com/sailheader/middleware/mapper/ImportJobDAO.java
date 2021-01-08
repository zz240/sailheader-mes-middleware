package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.ImportJob;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportJobDAO {

    /**
     * 根据id 查询
     * @param id
     * @return
     */
    ImportJob selectByPrimaryKey(Long id);

    /**
     * 插入
     * @param record
     * @return
     */
    int insertSelective(ImportJob record);

}
