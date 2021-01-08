package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.AddressRestMapping;
import org.springframework.stereotype.Repository;

@Repository
public interface AddressRestMappingDAO {
    /**
     * 根据id 查询
     * @param id
     * @return
     */
    AddressRestMapping selectByPrimaryKey(Long id);
}
