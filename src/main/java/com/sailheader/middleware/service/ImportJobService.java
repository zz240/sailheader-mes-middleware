package com.sailheader.middleware.service;

import com.sailheader.middleware.mapper.ImportJobDAO;
import com.sailheader.middleware.model.ImportJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ImportJobService {

    @Autowired
    private ImportJobDAO importJobDAO;

    public ImportJob findImportJobById(Long id){
        return importJobDAO.selectByPrimaryKey(id);
    }

}
