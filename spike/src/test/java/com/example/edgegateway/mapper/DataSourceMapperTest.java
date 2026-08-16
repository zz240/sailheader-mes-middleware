package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.jdbc.Sql;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisPlusTest
@Sql("/schema.sql")
class DataSourceMapperTest {

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Test
    void savesAndReadsBackDataSource() {
        DataSource ds = new DataSource();
        ds.setSiteId(1L);
        ds.setName("erp-mysql");
        ds.setType(DataSourceType.MYSQL);
        ds.setAddress("jdbc:mysql://localhost:3306/cx");
        ds.setAddition(Map.of("username", "sa"));
        dataSourceMapper.insert(ds);

        DataSource found = dataSourceMapper.selectOne(
                new LambdaQueryWrapper<DataSource>().eq(DataSource::getName, "erp-mysql"));

        assertThat(found).isNotNull();
        assertThat(found.getType()).isEqualTo(DataSourceType.MYSQL);
        assertThat(found.getAddress()).isEqualTo("jdbc:mysql://localhost:3306/cx");
        assertThat(found.getAddition()).containsEntry("username", "sa");
    }

    @Test
    void rejectsDuplicateNameInSameSite() {
        DataSource a = new DataSource();
        a.setSiteId(1L);
        a.setName("dup");
        a.setType(DataSourceType.HTTP);
        dataSourceMapper.insert(a);

        DataSource b = new DataSource();
        b.setSiteId(1L);
        b.setName("dup");
        b.setType(DataSourceType.HTTP);

        assertThatThrownBy(() -> dataSourceMapper.insert(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
