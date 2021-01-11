package com.sailheader.middleware.mapper;

import com.sailheader.middleware.model.EnterpriseRouteMapping;
import org.springframework.stereotype.Repository;

@Repository
public interface EnterpriseRouteMappingDAO {

    int insert(EnterpriseRouteMapping record);

    int deleteByPrimaryKey(Long id);

    int updateByPrimaryKey(EnterpriseRouteMapping enterpriseRouteMapping);

    EnterpriseRouteMapping selectByPrimaryKey(Long id);

    EnterpriseRouteMapping selectByEnterpriseCode(String code);


}
