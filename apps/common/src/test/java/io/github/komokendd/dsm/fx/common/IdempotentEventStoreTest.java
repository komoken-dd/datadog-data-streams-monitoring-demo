package io.github.komokendd.dsm.fx.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotentEventStoreTest {
    @Test
    void onlyTheFirstClaimOwnsAnEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq("event-1"))).thenReturn(1, 0);
        var store = new IdempotentEventStore(jdbc, "position_service");

        assertThat(store.claim("event-1")).isTrue();
        assertThat(store.claim("event-1")).isFalse();
    }

    @Test
    void rejectsUnsafeSchemaIdentifiers() {
        assertThatThrownBy(() -> new IdempotentEventStore(mock(JdbcTemplate.class), "public; drop schema public"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
