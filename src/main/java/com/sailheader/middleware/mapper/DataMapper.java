package com.sailheader.middleware.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sailheader.middleware.model.Action;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * saas数据导入 Mapper 接口
 * </p>
 *
 * @author Steven
 * @since 2021-03-05
 */
public interface DataMapper {
    @Delete("delete from ${tableName} where id > 0")
    int deleteByTableName(String tableName);

    @Select("select column_name as columnName from information_schema.columns where table_name=#{tableName} and table_schema='public'")
    List<Map<String, String>> selectFieldByTableName(String tableName);

    @Insert("<script>" +
            "insert into ${tableName}" +
            "<foreach collection='field' item='item' index='index' open='(' separator=',' close=')'>" +
            "   ${item}" +
            "</foreach>" +
            "values" +
            "   <foreach collection='inputData' item='item' index='index' separator=','>" +
            "       <foreach collection='item' item='item' index='index' open='(' separator=',' close=')'>" +
            "           <if test='item instanceof com.alibaba.fastjson.JSON'> "  +
            "               #{item,typeHandler=com.sailheader.middleware.common.helper.JsonTypeHandlerPg}" +
            "           </if>" +
            "           <if test='!(item instanceof com.alibaba.fastjson.JSON)'> "  +
            "               #{item}" +
            "           </if>" +
            "       </foreach>" +
            "   </foreach>" +
            "</script>")
    int insertAll(Map<String, ?> map);
}
