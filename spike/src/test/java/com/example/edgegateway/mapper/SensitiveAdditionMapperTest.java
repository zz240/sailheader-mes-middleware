package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisPlusTest
@Sql("/schema.sql")
class SensitiveAdditionMapperTest {

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private javax.sql.DataSource javaxDataSource;

    @Test
    void additionPasswordIsEncryptedAtRestAndDecryptedOnRead() {
        DataSource dataSource = new DataSource();
        dataSource.setSiteId(1L);
        dataSource.setName("mysql-secret");
        dataSource.setType(DataSourceType.MYSQL);
        dataSource.setAddress("jdbc:mysql://localhost:3306/x");
        dataSource.setAddition(Map.of(
                "url", "jdbc:mysql://localhost:3306/x",
                "username", "root",
                "password", "plain-secret"));
        dataSourceMapper.insert(dataSource);

        String stored = new JdbcTemplate(javaxDataSource).queryForObject(
                "SELECT addition FROM data_source WHERE id = ?", String.class, dataSource.getId());
        assertThat(stored).contains("enc:AESGCM:").doesNotContain("plain-secret");

        DataSource loaded = dataSourceMapper.selectById(dataSource.getId());
        assertThat(loaded.getAddition().get("password")).isEqualTo("plain-secret");
        assertThat(loaded.getAddition().get("username")).isEqualTo("root");
    }
}
