package io.github.komokendd.dsm.brokerage.buyingpower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReservationServiceTest {
    private final BuyingPowerRepository repository = mock(BuyingPowerRepository.class);
    private final ReservationService service = new ReservationService(repository);

    @Test
    void buyReservesCashAndRecordsReservation() {
        account("A00001", 30_000_000, 280_000);
        when(repository.findReservation("o-1")).thenReturn(Optional.empty());
        when(repository.reserveCash("A00001", Jpy.of(280_000))).thenReturn(true);

        var response = service.reserve(buy("A00001", "o-1"));

        assertThat(response.status()).isEqualTo(BuyingPowerReservation.RESERVED);
        assertThat(response.reservedAmountJpy()).isEqualByComparingTo("280000");
        assertThat(response.buyingPowerJpy()).isEqualByComparingTo("29720000");
        verify(repository).insertReservation(any());
    }

    @Test
    void insufficientBuyingPowerIsRejectedWithoutRecordingReservation() {
        account("A00451", 0, 0);
        when(repository.findReservation("o-1")).thenReturn(Optional.empty());
        when(repository.reserveCash(anyString(), any())).thenReturn(false);

        var response = service.reserve(buy("A00451", "o-1"));

        assertThat(response.status()).isEqualTo(BuyingPowerReservation.REJECTED);
        assertThat(response.reason()).isEqualTo(BuyingPowerReservation.INSUFFICIENT_BUYING_POWER);
        verify(repository, never()).insertReservation(any());
    }

    @Test
    void insufficientSellableQuantityIsRejected() {
        account("A00001", 0, 0);
        when(repository.findReservation("o-1")).thenReturn(Optional.empty());
        when(repository.reserveShares(eq("A00001"), eq("D101"), anyLong())).thenReturn(false);

        var response = service.reserve(new BuyingPowerReservation.Request(
                "A00001", "o-1", "D101", "SELL", "MARKET", 100_000, null));

        assertThat(response.reason()).isEqualTo(BuyingPowerReservation.INSUFFICIENT_SELLABLE_QUANTITY);
    }

    @Test
    void unknownAccountIsRejected() {
        when(repository.findReservation("o-1")).thenReturn(Optional.empty());
        when(repository.findAccount("Z99999")).thenReturn(Optional.empty());

        var response = service.reserve(buy("Z99999", "o-1"));

        assertThat(response.reason()).isEqualTo(BuyingPowerReservation.UNKNOWN_ACCOUNT);
        verify(repository, never()).reserveCash(anyString(), any());
    }

    @Test
    void repeatedOrderIdReturnsTheExistingReservation() {
        account("A00001", 30_000_000, 280_000);
        when(repository.findReservation("o-1")).thenReturn(Optional.of(BuyingPowerRulesTest.buyReservation(100, "280000")));

        var response = service.reserve(buy("A00001", "o-1"));

        assertThat(response.status()).isEqualTo(BuyingPowerReservation.RESERVED);
        assertThat(response.reservedAmountJpy()).isEqualByComparingTo("280000");
        verify(repository, never()).reserveCash(anyString(), any());
        verify(repository, never()).insertReservation(any());
    }

    @Test
    void releaseIsIdempotent() {
        var open = BuyingPowerRulesTest.buyReservation(100, "280000");
        when(repository.lockReservation("o-1")).thenReturn(Optional.of(open));

        var first = service.release("o-1");
        assertThat(first.status()).isEqualTo("RELEASED");
        assertThat(first.releasedAmountJpy()).isEqualByComparingTo("280000");
        verify(repository).applyToAccount(eq("A00001"), any());

        var closed = new ReservationState(open.orderId(), open.accountId(), open.symbol(), open.side(),
                open.orderType(), open.quantity(), open.reservedAmountJpy(), open.reservedAmountJpy(), 0, 0,
                ReservationState.Status.RELEASED);
        when(repository.lockReservation("o-1")).thenReturn(Optional.of(closed));
        var second = service.release("o-1");
        assertThat(second.releasedAmountJpy()).isEqualByComparingTo("0");
        verify(repository).applyToAccount(eq("A00001"), any());

        when(repository.lockReservation("missing")).thenReturn(Optional.empty());
        assertThat(service.release("missing").status()).isEqualTo("NOT_FOUND");
    }

    private void account(String accountId, long cash, long reserved) {
        when(repository.findAccount(accountId)).thenReturn(Optional.of(new BuyingPowerRepository.AccountSnapshot(
                accountId, Jpy.of(cash), Jpy.of(reserved), Instant.EPOCH)));
    }

    private static BuyingPowerReservation.Request buy(String accountId, String orderId) {
        return new BuyingPowerReservation.Request(
                accountId, orderId, "D101", "BUY", "LIMIT", 100, new BigDecimal("2800.0"));
    }
}
