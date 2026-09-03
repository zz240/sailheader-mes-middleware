package com.example.edgegateway.model;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ScheduleConfig 是调度深 module 的 seam：解析/校验/编译（fixedRate 或 cron+zone）在此集中。 */
class ScheduleConfigTest {

    @Test
    void noneIsNotRecurring() {
        ScheduleConfig config = ScheduleConfig.of(ScheduleType.NONE, null, null, null, null, null);
        assertThat(config.recurring()).isFalse();
        assertThat(config.interval()).isFalse();
        assertThat(config.internalCron()).isNull();
    }

    @Test
    void nullTypeTreatsAsNone() {
        assertThat(ScheduleConfig.of(null, null, null, null, null, null).recurring()).isFalse();
    }

    @Test
    void intervalMinutesCompilesToFixedRate() {
        ScheduleConfig config = ScheduleConfig.of(ScheduleType.INTERVAL, 5, "MINUTES", null, null, null);
        assertThat(config.interval()).isTrue();
        assertThat(config.fixedRateMs()).isEqualTo(5L * 60_000L);
        assertThat(config.internalCron()).isNull();
        ScheduleConfig.Plan plan = config.plan();
        assertThat(plan.isFixedRate()).isTrue();
        assertThat(plan.fixedRateMs()).isEqualTo(5L * 60_000L);
    }

    @Test
    void intervalHoursCompilesToFixedRate() {
        ScheduleConfig config = ScheduleConfig.of(ScheduleType.INTERVAL, 2, "HOURS", null, null, null);
        assertThat(config.fixedRateMs()).isEqualTo(2L * 3_600_000L);
    }

    @Test
    void nonIntervalHasNoFixedRate() {
        ScheduleConfig config = ScheduleConfig.of(ScheduleType.DAILY, null, null, "08:30", null, null);
        assertThatIllegalStateException()
                .isThrownBy(config::fixedRateMs)
                .withMessageContaining("只有 INTERVAL");
    }

    @Test
    void dailyCompilesToCron() {
        ScheduleConfig config = ScheduleConfig.of(ScheduleType.DAILY, null, null, "08:30", null, null);
        assertThat(config.internalCron()).isEqualTo("0 30 8 * * *");
        ScheduleConfig.Plan plan = config.plan();
        assertThat(plan.isCron()).isTrue();
        assertThat(plan.cron()).isEqualTo("0 30 8 * * *");
    }

    @Test
    void weeklyCompilesToCronWithWeekday() {
        ScheduleConfig config = ScheduleConfig.of(ScheduleType.WEEKLY, null, null, "18:05", 5, null);
        assertThat(config.internalCron()).isEqualTo("0 5 18 * * 5");
    }

    @Test
    void zoneFallsBackToSystemDefaultWhenBlank() {
        assertThat(ScheduleConfig.of(ScheduleType.DAILY, null, null, "08:30", null, null).zone())
                .isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void zoneUsesConfiguredTimezone() {
        ScheduleConfig config = ScheduleConfig.of(ScheduleType.DAILY, null, null, "08:30", null, "Asia/Shanghai");
        assertThat(config.zone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void fromJobMapsFlatFields() {
        Job job = new Job();
        job.setId(7L);
        job.setScheduleType(ScheduleType.INTERVAL);
        job.setIntervalValue(10);
        job.setIntervalUnit("MINUTES");
        job.setTimezone("Asia/Shanghai");

        ScheduleConfig config = ScheduleConfig.from(job);
        assertThat(config.type()).isEqualTo(ScheduleType.INTERVAL);
        assertThat(config.intervalUnit()).isEqualTo(IntervalUnit.MINUTES);
        assertThat(config.plan().fixedRateMs()).isEqualTo(10L * 60_000L);
        assertThat(config.zone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void rejectsNonPositiveIntervalValue() {
        assertThatThrownBy(() -> ScheduleConfig.of(ScheduleType.INTERVAL, 0, "MINUTES", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正整数");
    }

    @Test
    void rejectsInvalidIntervalUnit() {
        assertThatThrownBy(() -> ScheduleConfig.of(ScheduleType.INTERVAL, 5, "SECONDS", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MINUTES");
    }

    @Test
    void rejectsInvalidDailyTime() {
        assertThatThrownBy(() -> ScheduleConfig.of(ScheduleType.DAILY, null, null, "25:99", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行时间");
    }

    @Test
    void rejectsWeeklyWithoutWeekday() {
        assertThatThrownBy(() -> ScheduleConfig.of(ScheduleType.WEEKLY, null, null, "09:00", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("周几");
    }

    @Test
    void rejectsInvalidTimezone() {
        assertThatThrownBy(() -> ScheduleConfig.of(ScheduleType.DAILY, null, null, "09:00", null, "Nope/Zone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时区非法");
    }
}
