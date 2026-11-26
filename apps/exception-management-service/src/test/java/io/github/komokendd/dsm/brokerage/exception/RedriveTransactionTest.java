package io.github.komokendd.dsm.brokerage.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Verifies committed JDBC state through Spring's actual annotation interceptor. */
class RedriveTransactionTest {
    private JdbcTemplate jdbc;
    private RedriveService service;
    private KafkaTemplate<String, Object> kafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:redrive-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create schema exception_management_service");
        jdbc.execute("create table exception_management_service.notification_dead_letters ("
                + "event_id varchar(64) primary key, received_at timestamp, resolved_at timestamp, redrive_event_id varchar(64))");
        jdbc.update("insert into exception_management_service.notification_dead_letters (event_id) values ('dead-1'), ('dead-2')");
        var repository = new NotificationDeadLetterRepository(jdbc) {
            @Override
            List<NotificationDeadLetter> lockUnresolved(int limit) {
                // PostgreSQL SKIP LOCKED is covered by the repository's SQL; this
                // test isolates transaction rollback using real JDBC mutations.
                return List.of(DeadLetters.row("dead-1", "n1", Instant.EPOCH),
                        DeadLetters.row("dead-2", "n2", Instant.EPOCH));
            }
        };
        kafka = mock(KafkaTemplate.class);
        var target = new RedriveService(repository, kafka, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "redrive");
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        service = (RedriveService) factory.getProxy();
    }

    @Test
    void checkedFailureOnSecondSendRollsBackFirstRowsCommittedStatus() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null),
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> service.redrive()).isInstanceOf(ExecutionException.class);
        assertAllUnresolved();
    }

    @Test
    void timeoutAfterFirstAcknowledgmentRollsBackTheDatabaseBatch() {
        var timeout = new CompletableFuture<SendResult<String, Object>>() {
            @Override
            public SendResult<String, Object> get(long value, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException("ack timed out");
            }
        };
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null), timeout);
        assertThatThrownBy(() -> service.redrive()).isInstanceOf(TimeoutException.class);
        assertAllUnresolved();
    }

    @Test
    void interruptionRestoresFlagAndRollsBackTheDatabaseBatch() {
        var interrupted = new CompletableFuture<SendResult<String, Object>>() {
            @Override
            public SendResult<String, Object> get(long value, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("stopped");
            }
        };
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null), interrupted);
        try {
            assertThatThrownBy(() -> service.redrive()).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertAllUnresolved();
    }

    private void assertAllUnresolved() {
        assertThat(jdbc.queryForObject("select count(*) from exception_management_service.notification_dead_letters"
                + " where resolved_at is null and redrive_event_id is null", Integer.class)).isEqualTo(2);
    }
}
