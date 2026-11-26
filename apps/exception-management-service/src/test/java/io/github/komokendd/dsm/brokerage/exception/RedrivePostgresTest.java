package io.github.komokendd.dsm.brokerage.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "BROKERAGE_TEST_POSTGRES_PORT", matches = "[0-9]+")
class RedrivePostgresTest {
    private JdbcTemplate jdbc;
    private NotificationDeadLetterRepository repository;
    private DataSourceTransactionManager manager;
    private KafkaTemplate<String, Object> kafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        String port = System.getenv("BROKERAGE_TEST_POSTGRES_PORT");
        String user = System.getenv().getOrDefault("BROKERAGE_TEST_POSTGRES_USER", "audit_admin");
        var admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/postgres", user, ""));
        if (admin.queryForObject("select count(*) from pg_database where datname='notification_redrive_test'", Integer.class) == 0) {
            admin.execute("create database notification_redrive_test");
        }
        var source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/notification_redrive_test", user, "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("drop schema if exists exception_management_service cascade");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__exception_management_service.sql")).execute(source);
        repository = new NotificationDeadLetterRepository(jdbc);
        repository.insert(DeadLetters.row("dead-1", "n1", Instant.EPOCH));
        repository.insert(DeadLetters.row("dead-2", "n2", Instant.EPOCH.plusSeconds(1)));
        manager = new DataSourceTransactionManager(source);
        kafka = mock(KafkaTemplate.class);
    }

    private RedriveService service(int batchSize) {
        var target = new RedriveService(repository, kafka, Clock.systemUTC(), () -> "redrive", batchSize, Duration.ofSeconds(5));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (RedriveService) proxy.getProxy();
    }

    @Test
    void checkedPublishFailureRollsBackEarlierResolutionInPostgres() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null),
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> service(100).redrive()).isInstanceOf(ExecutionException.class);
        assertThat(repository.unresolvedSummary().count()).isEqualTo(2);
    }

    @Test
    void boundedBatchResumesOnNextRequestWithAccurateRemainingCount() throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        assertThat(service(1).redrive().remainingCount()).isEqualTo(1);
        assertThat(repository.unresolvedSummary().count()).isEqualTo(1);
        assertThat(service(1).redrive().remainingCount()).isZero();
        assertThat(repository.unresolvedSummary().count()).isZero();
    }

    @Test
    void concurrentBatchesSkipLockedRowsRatherThanBlockingOrDuplicating() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(repository.lockUnresolved(1)).extracting(NotificationDeadLetter::eventId).containsExactly("dead-1");
                var next = executor.submit(() -> new TransactionTemplate(manager).execute(
                        ignored -> repository.lockUnresolved(1).getFirst().eventId()));
                try {
                    assertThat(next.get(3, TimeUnit.SECONDS)).isEqualTo("dead-2");
                } catch (Exception exception) {
                    throw new IllegalStateException("A concurrent batch should not wait for the first row lock", exception);
                }
            });
        }
    }
}
