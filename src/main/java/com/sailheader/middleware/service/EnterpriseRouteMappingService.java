package com.sailheader.middleware.service;

import com.alibaba.fastjson.JSONObject;
import com.sailheader.middleware.mapper.EnterpriseRouteMappingDAO;
import com.sailheader.middleware.model.EnterpriseRouteMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class EnterpriseRouteMappingService {

    @Autowired
    private EnterpriseRouteMappingDAO enterpriseRouteMappingDAO;

    /**
     * 插入企业路由信息映射
     * @param record
     * @return
     */
    public Integer insert(EnterpriseRouteMapping record){
        return enterpriseRouteMappingDAO.insert(record);
    }

    /**
     * 根据id删除
     * @param id
     * @return
     */
    public Integer deleteByPrimaryKey(Long id){
        return enterpriseRouteMappingDAO.deleteByPrimaryKey(id);
    }

    /**
     * 根据id更新
     * @param enterpriseRouteMapping
     * @return
     */
    public Integer updateByPrimaryKey(EnterpriseRouteMapping enterpriseRouteMapping){
        return enterpriseRouteMappingDAO.updateByPrimaryKey(enterpriseRouteMapping);
    }

    /**
     * 根据 id 查询企业路由映射信息
     * @param id
     * @return
     */
    public EnterpriseRouteMapping selectByPrimaryKey(Long id){
        return enterpriseRouteMappingDAO.selectByPrimaryKey(id);
    }

    /**
     * 根据 企业编码 查询信息
     * @param code
     * @return
     */
    public EnterpriseRouteMapping selectByEnterpriseCode(String code){
        return enterpriseRouteMappingDAO.selectByEnterpriseCode(code);
    }



}
