package com.sailheader.middleware.service;

import com.sailheader.middleware.mapper.AddressRestMappingDAO;
import com.sailheader.middleware.model.AddressRestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AddressRestMappingService {

    @Autowired
    private AddressRestMappingDAO addressRestMappingDAO;

    public AddressRestMapping findAddressRestMappingById(Long id){
        return addressRestMappingDAO.selectByPrimaryKey(id);
    }
}
