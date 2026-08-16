package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.edgegateway.model.DeadLetter;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeadLetterMapper extends BaseMapper<DeadLetter> {
}
