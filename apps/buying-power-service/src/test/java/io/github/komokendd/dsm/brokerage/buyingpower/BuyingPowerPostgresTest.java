package io.github.komokendd.dsm.brokerage.buyingpower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.schema.BuyingPowerUpdatedEvent;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only against an explicitly enabled isolated local PostgreSQL instance. Never uses application DATABASE_URL. */
@EnabledIfEnvironmentVariable(named = "BROKERAGE_TEST_POSTGRES_PORT", matches = "[0-9]+")
class BuyingPowerPostgresTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ReservationService reservations;
    private BuyingPowerLedger ledger;

    @BeforeEach
    void prepare() {
        String port = System.getenv("BROKERAGE_TEST_POSTGRES_PORT");
        String user = System.getenv().getOrDefault("BROKERAGE_TEST_POSTGRES_USER", "audit_admin");
        var admin = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/postgres", user, "");
        var adminJdbc = new JdbcTemplate(admin);
        if (adminJdbc.queryForObject("select count(*) from pg_database where datname='buying_power_test'", Integer.class) == 0) {
            adminJdbc.execute("create database buying_power_test");
        }
        var datasource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/buying_power_test", user, "");
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("drop schema if exists buying_power_service cascade");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__buying_power_service.sql"),
                new ClassPathResource("db/migration/V2__reservation_lifecycle_progress.sql"),
                new ClassPathResource("db/migration/V3__reservation_cancellation_fence.sql")).execute(datasource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        var repository = new BuyingPowerRepository(jdbc);
        reservations = new ReservationService(repository);
        @SuppressWarnings("unchecked") KafkaTemplate<String, BuyingPowerUpdatedEvent> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        ledger = new BuyingPowerLedger(repository, new IdempotentEventStore(jdbc, "buying_power_service"), kafka, Clock.systemUTC());
        jdbc.update("insert into buying_power_service.accounts(account_id,cash_jpy,reserved_jpy) values('A00001',2800000,0)");
        jdbc.update("insert into buying_power_service.sellable(account_id,symbol,quantity,reserved_quantity) values('A00001','D101',1000,0)");
    }

    @Test
    void buyExpiryBeforeFillDoesNotFundAnUnbackedSecondOrderAndDuplicatesAreHarmless() {
        reserve("first", "BUY", 1000);
        var expiry = expiry("BUY");
        transactions.executeWithoutResult(status -> ledger.applyExpiry(expiry));
        assertThat(number("reserved_jpy", "accounts")).isEqualTo(1_400_000);
        reserve("second", "BUY", 500);
        var fill = fill("BUY");
        transactions.executeWithoutResult(status -> ledger.applyExecution(fill));
        transactions.executeWithoutResult(status -> ledger.applyExecution(fill));
        transactions.executeWithoutResult(status -> ledger.applyExpiry(expiry));
        assertThat(number("cash_jpy", "accounts")).isEqualTo(1_400_000);
        assertThat(number("reserved_jpy", "accounts")).isEqualTo(1_400_000);
        assertThat(jdbc.queryForObject("select status from buying_power_service.reservations where order_id='first'", String.class))
                .isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select count(*) from buying_power_service.processed_events", Integer.class)).isEqualTo(2);
    }

    @Test
    void sellExpiryBeforeFillDoesNotAllowSellingUnreflectedExecutedSharesTwice() {
        reserve("first", "SELL", 1000);
        transactions.executeWithoutResult(status -> ledger.applyExpiry(expiry("SELL")));
        reserve("second", "SELL", 500);
        transactions.executeWithoutResult(status -> ledger.applyExecution(fill("SELL")));
        assertThat(jdbc.queryForObject("select quantity from buying_power_service.sellable", Long.class)).isEqualTo(500);
        assertThat(jdbc.queryForObject("select reserved_quantity from buying_power_service.sellable", Long.class)).isEqualTo(500);
        var rejected = transactions.execute(status -> reservations.reserve(request("third", "SELL", 100)));
        assertThat(rejected.reason()).isEqualTo(BuyingPowerReservation.INSUFFICIENT_SELLABLE_QUANTITY);
    }

    @Test
    void cancellationBeforeReservationCreatesAFenceThatRejectsALateHttpCommit() {
        transactions.executeWithoutResult(status -> reservations.release("late"));
        var late = transactions.execute(status -> reservations.reserve(request("late", "BUY", 100)));
        assertThat(late.reason()).isEqualTo(BuyingPowerReservation.RESERVATION_CANCELLED);
        assertThat(number("reserved_jpy", "accounts")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from buying_power_service.reservations", Integer.class)).isZero();
    }

    private void reserve(String id, String side, long quantity) {
        var result = transactions.execute(status -> reservations.reserve(request(id, side, quantity)));
        assertThat(result.status()).isEqualTo("RESERVED");
    }
    private BuyingPowerReservation.Request request(String id, String side, long quantity) {
        return new BuyingPowerReservation.Request("A00001", id, "D101", side, "LIMIT", quantity, Jpy.price(new BigDecimal("2800")));
    }
    private long number(String column, String table) {
        return jdbc.queryForObject("select " + column + " from buying_power_service." + table, Long.class);
    }
    private ExecutionReportEvent fill(String side) {
        Instant now = Instant.now();
        return ExecutionReportEvent.newBuilder().setEventId("fill-1").setOccurredAt(now)
                .setAccountId("A00001").setOrderId("first").setSymbol("D101").setSide(side)
                .setOrderType("LIMIT").setQuantity(1000).setLimitPrice(Jpy.price(new BigDecimal("2800")))
                .setReservedAmountJpy(side.equals("BUY") ? Jpy.of(2_800_000) : Jpy.of(0))
                .setExecutionId("execution-1").setExecutionSequence(1).setExecutedQuantity(500)
                .setExecutionPrice(Jpy.price(new BigDecimal("2800"))).setExecutionAmountJpy(Jpy.of(1_400_000))
                .setCumulativeQuantity(500).setLeavesQuantity(500).setOrderStatus("PARTIALLY_FILLED")
                .setAcceptedAt(now.minusSeconds(5)).setExecutedAt(now).build();
    }
    private OrderExpiredEvent expiry(String side) {
        Instant now = Instant.now();
        return OrderExpiredEvent.newBuilder().setEventId("expiry-1").setOccurredAt(now)
                .setAccountId("A00001").setOrderId("first").setSymbol("D101").setSide(side)
                .setOrderType("LIMIT").setQuantity(1000).setLimitPrice(Jpy.price(new BigDecimal("2800")))
                .setReservedAmountJpy(side.equals("BUY") ? Jpy.of(2_800_000) : Jpy.of(0))
                .setCumulativeQuantity(500).setExpiredQuantity(500).setReason("SESSION_CLOSE")
                .setAcceptedAt(now.minusSeconds(5)).setExpiredAt(now).build();
    }
}
