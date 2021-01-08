package com.sailheader.middleware.rest;

import com.sailheader.middleware.common.vo.OperationResult;
import com.sailheader.middleware.constant.StateCode;
import com.sailheader.middleware.mapper.ImportJobDAO;
import com.sailheader.middleware.model.ImportJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImportJobController {

    @Autowired
    private ImportJobDAO importJobDAO;


    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/import/data/jobs")
    public OperationResult createImportJob(/*@RequestBody */ImportJob importJob){
        importJob.setRestUrl("httww.kuaidi100.com/p://wquery?type=zhongtong&postid=75427719786230");
//        int i = importJobDAO.insertSelective(importJob);

        return new OperationResult().setData(null).setMessage("添加同步任务失败").setStateCode(StateCode.SYSTEM_SUCCESS.getValue());
    }
}
