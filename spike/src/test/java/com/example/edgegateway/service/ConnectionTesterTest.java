package com.example.edgegateway.service;

import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTesterTest {

    private final ConnectionTester tester = new ConnectionTester();

    @Test
    void unknownTypeReturnsFail() {
        DataSource ds = new DataSource();
        ds.setType(null);

        assertThat(tester.test(ds).ok()).isFalse();
    }

    @Test
    void invalidAdditionReturnsFail() {
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition("not-json");

        assertThat(tester.test(ds).ok()).isFalse();
    }
}
