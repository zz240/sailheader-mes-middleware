package com.example.edgegateway.migration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照列容量迁移（#141）的**真库**用例：在临时库里从 V1 那种 {@code TEXT} 起点跑一遍
 * {@code V6__snapshot_mediumtext.sql}，断言列真的变宽、幂等、且不会把已经改宽的列改窄，
 * 最后让一条 344 KB 的报告真的落库再读回来。
 *
 * <p><b>为什么只能在真 MySQL 上测</b>：H2（测试集的其余部分）里 {@code TEXT} 本来就是 CLOB，
 * 「超过 65535 字节写不进去」这条界线在 H2 上**根本不存在**——本票要修的那个失败方式在 H2 上
 * 复现不出来，拿 H2 断言「300 KB 能落库」是假绿。所以这里连真 MySQL（默认 {@code localhost:3306}，
 * 账号与 {@code spike/docker-compose.yml} 一致），库名固定 {@code edge_gateway_snapshot_probe}，
 * 每个用例前重建、跑完删掉。</p>
 *
 * <p>连不上（CI、别人的机器、没起 docker）就**整类跳过**：{@code mvn test} 仍然全绿，
 * 本机有那台 MySQL 时是真跑。连接参数可用 {@code -Dedge.test.mysql.host/port/user/password} 覆盖。</p>
 */
class SnapshotCapacityMigrationTest {

    private static final String HOST = System.getProperty("edge.test.mysql.host", "localhost");
    private static final String PORT = System.getProperty("edge.test.mysql.port", "3306");
    private static final String USER = System.getProperty("edge.test.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("edge.test.mysql.password", "root");

    /** 临时库名（固定：每个用例前重建，跑完删）。**绝不碰 edge_gateway**。 */
    private static final String SCRATCH = "edge_gateway_snapshot_probe";

    private static final String MIGRATION = "db/migration/V6__snapshot_mediumtext.sql";

    /** 本票要修的三列：承载报文的那三格。 */
    private static final String[][] SNAPSHOT_COLUMNS = {
            {"message_step", "data_snapshot"},
            {"message_step", "input_snapshot"},
            {"job_run", "input_snapshot"},
    };

    /** V1 那种起点：三列都是 {@code TEXT}（65535 字节）——迁移要把它抬起来。 */
    private static final String[] V1_COLUMNS = {
            "CREATE TABLE message_step (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " message_id VARCHAR(64), data_snapshot TEXT, input_snapshot TEXT)",
            "CREATE TABLE job_run (id BIGINT AUTO_INCREMENT PRIMARY KEY, input_snapshot TEXT)",
    };

    /** A7BIG 实测值（#141 票面）：3000 行 / 344 KB 的一条报文。 */
    private static final String REPORT = "{\"blob\":\"" + "x".repeat(344 * 1024) + "\"}";

    @BeforeAll
    static void connectOrSkip() {
        try (Connection connection = admin()) {
            exec(connection, "DROP DATABASE IF EXISTS " + SCRATCH,
                    "CREATE DATABASE " + SCRATCH + " DEFAULT CHARACTER SET utf8mb4");
        } catch (Exception e) {
            Assumptions.abort("没有可用的真 MySQL（" + HOST + ":" + PORT + "：" + e.getMessage() + "）——"
                    + "快照列容量在 H2 上复现不出来，本类整类跳过（本机起了 docker-compose 的 mysql 就真跑）");
        }
    }

    @AfterAll
    static void dropScratch() {
        try (Connection connection = admin()) {
            exec(connection, "DROP DATABASE IF EXISTS " + SCRATCH);
        } catch (Exception ignored) {
            // 收摊失败不影响结论：库名固定，下次 @BeforeEach 会重建。
        }
    }

    /** 每个用例都从「V1 的 TEXT 起点」重来一次，用例之间不串味。 */
    @BeforeEach
    void freshLibrary() throws SQLException {
        try (Connection connection = admin()) {
            exec(connection, "DROP DATABASE IF EXISTS " + SCRATCH,
                    "CREATE DATABASE " + SCRATCH + " DEFAULT CHARACTER SET utf8mb4");
        }
        try (Connection connection = scratch()) {
            exec(connection, V1_COLUMNS);
        }
        assertThat(dataType("message_step", "data_snapshot")).as("起点确实是 TEXT").isEqualTo("text");
    }

    /**
     * 验收项 1、3：三列都抬到 {@code MEDIUMTEXT}，而且超过 65535 字节的报文**真的**落得进去、
     * 完整读得回来（旧列型上这一条会以 {@code Data too long for column} 失败）。
     */
    @Test
    void textColumnsBecomeMediumtextAndAThreeHundredKilobyteReportLandsWhole() throws SQLException {
        applyMigration();

        for (String[] column : SNAPSHOT_COLUMNS) {
            assertThat(dataType(column[0], column[1])).as(column[0] + "." + column[1]).isEqualTo("mediumtext");
        }
        for (String[] column : SNAPSHOT_COLUMNS) {
            insertSnapshot(column[0], column[1], REPORT);
            assertThat(readSnapshot(column[0], column[1])).as(column[0] + "." + column[1] + " 读回来的是整份")
                    .isEqualTo(REPORT);
        }
        assertThat(REPORT).as("这份报文确实超过旧列型的上限").hasSizeGreaterThan(65_535);
    }

    /**
     * 验收项 2（手工改宽过的那一档）：已经有人把列改成 {@code MEDIUMTEXT} 的库上执行迁移是
     * **no-op**——不报错、列型不动。运行库今天就是这个状态（`tools/shadow-site/README.md` 记的手工改宽）。
     */
    @Test
    void aManuallyWidenedColumnSurvivesTheMigrationUnchanged() throws SQLException {
        try (Connection connection = scratch()) {
            exec(connection, "ALTER TABLE message_step MODIFY COLUMN data_snapshot MEDIUMTEXT");
        }

        applyMigration();

        assertThat(dataType("message_step", "data_snapshot")).isEqualTo("mediumtext");
        assertThat(dataType("message_step", "input_snapshot")).isEqualTo("mediumtext");
        assertThat(dataType("job_run", "input_snapshot")).isEqualTo("mediumtext");
    }

    /**
     * 验收项 2 的另一半：**再跑一遍**是 no-op，而且库里那条大快照一根毛都没少
     * （「幂等」不是「第二次不报错」，是「第二次不动数据」）。
     */
    @Test
    void runningTheMigrationTwiceKeepsTheBigSnapshotIntact() throws SQLException {
        applyMigration();
        insertSnapshot("message_step", "data_snapshot", REPORT);

        applyMigration();

        assertThat(dataType("message_step", "data_snapshot")).isEqualTo("mediumtext");
        assertThat(readSnapshot("message_step", "data_snapshot")).isEqualTo(REPORT);
    }

    /** 已经有人改成 {@code LONGTEXT} 的库**不往回改窄**：更宽的列没道理被这一票压回来。 */
    @Test
    void anEvenWiderColumnIsNeverNarrowedBack() throws SQLException {
        try (Connection connection = scratch()) {
            exec(connection, "ALTER TABLE message_step MODIFY COLUMN data_snapshot LONGTEXT");
        }

        applyMigration();

        assertThat(dataType("message_step", "data_snapshot")).isEqualTo("longtext");
        assertThat(dataType("message_step", "input_snapshot")).isEqualTo("mediumtext");
        assertThat(dataType("job_run", "input_snapshot")).isEqualTo("mediumtext");
    }

    // ---------- 脚手架 ----------

    /** 跑真正的迁移文件（主资源里那一个，不是抄一份到测试里——抄一份就测不到真脚本了）。 */
    private static void applyMigration() throws SQLException {
        String script = readMigration();
        try (Connection connection = scratch(); Statement statement = connection.createStatement()) {
            for (String sql : statementsOf(script)) {
                statement.execute(sql);
            }
        }
    }

    private static String readMigration() {
        try (InputStream in = SnapshotCapacityMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (in == null) {
                throw new IllegalStateException("主资源里没有 " + MIGRATION);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 " + MIGRATION, e);
        }
    }

    /**
     * 按 {@code ;} 切语句并丢掉空块。迁移脚本里没有「字符串里带分号」的写法，
     * 所以这个切法够用（文件本身的写法是 V4 / V5 那一套：注释 + SET/PREPARE/EXECUTE）。
     */
    private static List<String> statementsOf(String script) {
        List<String> out = new ArrayList<>();
        for (String chunk : script.split(";")) {
            if (!chunk.isBlank()) {
                out.add(chunk);
            }
        }
        return out;
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(url(""), USER, PASSWORD);
    }

    private static Connection scratch() throws SQLException {
        return DriverManager.getConnection(url(SCRATCH), USER, PASSWORD);
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8";
    }

    private static void exec(Connection connection, String... sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String each : sql) {
                statement.execute(each);
            }
        }
    }

    /** 那一列的类型（`text` / `mediumtext` / `longtext`）：迁移干没干活只看它。 */
    private static String dataType(String table, String column) throws SQLException {
        String sql = "SELECT DATA_TYPE FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection connection = scratch(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCRATCH);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static void insertSnapshot(String table, String column, String value) throws SQLException {
        try (Connection connection = scratch();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + table + " (" + column + ") VALUES (?)")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    /** 最后一行那一格（每个用例的临时库是干净的，插入即这一格）。 */
    private static String readSnapshot(String table, String column) throws SQLException {
        try (Connection connection = scratch();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT " + column + " FROM " + table + " ORDER BY id DESC LIMIT 1")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
