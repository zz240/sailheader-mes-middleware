package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.ImportJob;
import com.sailheader.middleware.model.ImportJobExample;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportJobDAO {

    /**
     * 根据id 查询同步任务
     * @param id
     * @return
     */
    ImportJob selectByPrimaryKey(Long id);

    /**
     * 插入同步任务
     * @param record
     * @return
     */
    int insertSelective(ImportJob record);

    /**
     * 根据name查询同步任务
     * @param name
     * @return
     */
    ImportJob selectByName(String name);

    /**
     * 根据id更新同步任务
     * @return
     */
    int updateByPrimaryKey(ImportJob importJob);


    List<ImportJob> selectByExample(ImportJobExample example);


    int updateByPrimaryKeySelective(ImportJob importJob);
}
