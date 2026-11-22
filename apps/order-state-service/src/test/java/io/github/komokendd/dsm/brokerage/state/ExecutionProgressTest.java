package io.github.komokendd.dsm.brokerage.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionProgressTest {
    private static final Instant REFLECTED = Instant.parse("2026-11-20T01:00:03Z");
    private static final Instant ISSUED = Instant.parse("2026-11-20T01:00:01Z");
    private static final Instant DELIVERED = Instant.parse("2026-11-20T01:00:02Z");

    @Test
    void parkedMarksGiveTheSameResultInAnyArrivalOrder() {
        List<ExecutionMark> marks = List.of(
                mark(ExecutionMark.Type.BUYING_POWER_REFLECTED, REFLECTED),
                mark(ExecutionMark.Type.TRADE_REPORT_ISSUED, ISSUED),
                mark(ExecutionMark.Type.NOTIFICATION_DELIVERED, DELIVERED));
        ExecutionProgress expected = new ExecutionProgress(REFLECTED, ISSUED, NotificationStatus.DELIVERED, DELIVERED);

        for (List<ExecutionMark> order : OrderSnapshotTest.permutations(marks)) {
            assertThat(ExecutionProgress.INITIAL.applyAll(order)).as("arrival order %s", order).isEqualTo(expected);
        }
    }

    @Test
    void firstReflectionTimestampWins() {
        ExecutionProgress progress = ExecutionProgress.INITIAL
                .apply(mark(ExecutionMark.Type.BUYING_POWER_REFLECTED, REFLECTED))
                .apply(mark(ExecutionMark.Type.BUYING_POWER_REFLECTED, REFLECTED.plusSeconds(60)));

        assertThat(progress.buyingPowerReflectedAt()).isEqualTo(REFLECTED);
    }

    @Test
    void retryAndDeadLetterAreTrackedUntilDelivery() {
        ExecutionProgress progress = ExecutionProgress.INITIAL.apply(retrying(false));
        assertThat(progress.notificationStatus()).isEqualTo(NotificationStatus.RETRYING);

        progress = progress.apply(deadLetter());
        assertThat(progress.notificationStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(progress.notificationDeliveredAt()).isNull();
    }

    @Test
    void staleRetryDoesNotHideDeadLetterButRedriveDoes() {
        ExecutionProgress deadLettered = ExecutionProgress.INITIAL.apply(deadLetter());

        assertThat(deadLettered.apply(retrying(false)).notificationStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(deadLettered.apply(retrying(true)).notificationStatus()).isEqualTo(NotificationStatus.RETRYING);
    }

    @Test
    void deliveredIsFinal() {
        ExecutionProgress delivered = ExecutionProgress.INITIAL
                .apply(deadLetter())
                .apply(mark(ExecutionMark.Type.NOTIFICATION_DELIVERED, DELIVERED));

        assertThat(delivered.notificationStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(delivered.apply(retrying(true)).notificationStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(delivered.apply(deadLetter()).notificationDeliveredAt()).isEqualTo(DELIVERED);
    }

    @Test
    void tradeReportsMatchByExecutionIdAndOthersByEventId() {
        assertThat(mark(ExecutionMark.Type.TRADE_REPORT_ISSUED, ISSUED).matchKey())
                .isEqualTo(ExecutionMark.MatchKey.EXECUTION_ID);
        assertThat(mark(ExecutionMark.Type.BUYING_POWER_REFLECTED, REFLECTED).matchKey())
                .isEqualTo(ExecutionMark.MatchKey.EXECUTION_EVENT_ID);
        assertThat(deadLetter().matchKey()).isEqualTo(ExecutionMark.MatchKey.EXECUTION_EVENT_ID);
    }

    private static ExecutionMark mark(ExecutionMark.Type type, Instant at) {
        return new ExecutionMark(type, at, false);
    }

    private static ExecutionMark retrying(boolean redriven) {
        return new ExecutionMark(ExecutionMark.Type.NOTIFICATION_RETRYING, null, redriven);
    }

    private static ExecutionMark deadLetter() {
        return new ExecutionMark(ExecutionMark.Type.NOTIFICATION_DEAD_LETTER, null, false);
    }
}
