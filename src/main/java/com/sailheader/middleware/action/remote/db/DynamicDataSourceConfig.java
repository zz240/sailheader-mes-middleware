package com.sailheader.middleware.action.remote.db;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;

@Configuration
@MapperScan(basePackages = "com.sailheader.middleware.action.remote.db.dao", sqlSessionTemplateRef  = "dynamic.data.source.session.template")
public class DynamicDataSourceConfig {

    @Bean("dynamic.data.source")
    public DataSource dynamicDataSource() {
        AbstractRoutingDataSource dataSource = new DynamicDataSourceHandler();
        dataSource.setTargetDataSources(new HashMap<>());
        return dataSource;
    }

    /**
     * 使用 SqlSessionFactory 加载动态数据源
     * @return
     * @throws Exception
     */
    @Bean("dynamic.data.sql.session.factory")
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dynamic.data.source") DataSource dataSource) throws Exception {
        SqlSessionFactoryBean sqlSessionFactoryBean = new SqlSessionFactoryBean();
        sqlSessionFactoryBean.setDataSource(dataSource);
        return sqlSessionFactoryBean.getObject();
    }

    @Bean("dynamic.data.source.session.template")
    public SqlSessionTemplate sqlSessionTemplate(@Qualifier("dynamic.data.sql.session.factory") SqlSessionFactory sqlSessionFactory) throws Exception {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 事务管理
     * @return 事务管理实例
     */
    @Bean("dynamic.data.source.transaction.manager")
    public PlatformTransactionManager platformTransactionManager(@Qualifier("dynamic.data.source") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

}
