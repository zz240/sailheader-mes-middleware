package com.sailheader.middleware.service;

import com.sailheader.middleware.mapper.ImportJobDAO;
import com.sailheader.middleware.model.ImportJob;
import com.sailheader.middleware.model.ImportJobExample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ImportJobService {

    @Autowired
    private ImportJobDAO importJobDAO;

    public ImportJob findImportJobById(Long id){
        return importJobDAO.selectByPrimaryKey(id);
    }

    public ImportJob findImportJobByName(String name){
        return importJobDAO.selectByName(name);
    }

    public int insertSelective(ImportJob importJob){
        return importJobDAO.insertSelective(importJob);
    }

    /**
     * 同步任务对象的id更新内容
     * @param importJob
     * @return
     */
    public int updateByImportJob(ImportJob importJob){
        return importJobDAO.updateByPrimaryKey(importJob);
    }

    public List<ImportJob> findImportJobByStatus(String status){
        ImportJobExample example=  new ImportJobExample();
        example.createCriteria().andStatusEqualTo(status);
        return importJobDAO.selectByExample(example);
    }

    public int updateByPrimaryKeySelective(ImportJob importJob){
        return importJobDAO.updateByPrimaryKeySelective(importJob);
    }


}
