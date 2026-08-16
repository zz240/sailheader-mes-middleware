package com.example.edgegateway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WatermarkStore {

    private final JdbcTemplate jdbc;

    public WatermarkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long get(String key) {
        List<Long> values = jdbc.query(
                "select wm_value from watermark where job_key = ?",
                (rs, i) -> rs.getLong("wm_value"),
                key);
        return values.isEmpty() ? 0L : values.get(0);
    }

    public void put(String key, long value) {
        jdbc.update(
                "insert into watermark(job_key, wm_value) values (?, ?) "
                        + "on duplicate key update wm_value = values(wm_value)",
                key, value);
    }
}
