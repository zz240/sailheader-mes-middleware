package com.sailheader.middleware.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 企业路由映射表
 */
@Data
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnterpriseRouteMapping implements Serializable {
    private static final long serialVersionUID = 1L;

    //id
    private Long id;

    //企业编码
    private String code;

    //企业名称
    private String enterpriseName;

    //企业地址
    private String targetAddress;


}
