package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BuyingPowerRepository {
    static final String SCHEMA = "buying_power_service";

    private final JdbcTemplate jdbc;

    BuyingPowerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AccountSnapshot> findAccount(String accountId) {
        return jdbc.query(
                        "select account_id, cash_jpy, reserved_jpy, updated_at from buying_power_service.accounts"
                                + " where account_id = ?",
                        (rs, row) -> new AccountSnapshot(
                                rs.getString("account_id"),
                                rs.getBigDecimal("cash_jpy"),
                                rs.getBigDecimal("reserved_jpy"),
                                rs.getTimestamp("updated_at").toInstant()),
                        accountId)
                .stream()
                .findFirst();
    }

    List<SellablePosition> findSellable(String accountId) {
        return jdbc.query(
                "select symbol, quantity, reserved_quantity from buying_power_service.sellable"
                        + " where account_id = ? order by symbol",
                (rs, row) -> new SellablePosition(
                        rs.getString("symbol"), rs.getLong("quantity"), rs.getLong("reserved_quantity")),
                accountId);
    }

    /** Conditional update: concurrent reservations can never overdraw buying power. */
    boolean reserveCash(String accountId, BigDecimal amountJpy) {
        return jdbc.update(
                        "update buying_power_service.accounts set reserved_jpy = reserved_jpy + ?, updated_at = now()"
                                + " where account_id = ? and cash_jpy - reserved_jpy >= ?",
                        amountJpy, accountId, amountJpy)
                == 1;
    }

    /** Conditional update: concurrent SELL orders can never oversell holdings. */
    boolean reserveShares(String accountId, String symbol, long quantity) {
        return jdbc.update(
                        "update buying_power_service.sellable set reserved_quantity = reserved_quantity + ?,"
                                + " updated_at = now()"
                                + " where account_id = ? and symbol = ? and quantity - reserved_quantity >= ?",
                        quantity, accountId, symbol, quantity)
                == 1;
    }

    /** Throws DuplicateKeyException when the order already holds a reservation. */
    void insertReservation(ReservationState reservation) {
        jdbc.update(
                "insert into buying_power_service.reservations (order_id, account_id, symbol, side, order_type,"
                        + " quantity, reserved_amount_jpy, released_amount_jpy, reserved_quantity, released_quantity,"
                        + " status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.orderId(),
                reservation.accountId(),
                reservation.symbol(),
                reservation.side().name(),
                reservation.orderType(),
                reservation.quantity(),
                reservation.reservedAmountJpy(),
                reservation.releasedAmountJpy(),
                reservation.reservedQuantity(),
                reservation.releasedQuantity(),
                reservation.status().name());
    }

    Optional<ReservationState> findReservation(String orderId) {
        return queryReservation("select * from buying_power_service.reservations where order_id = ?", orderId);
    }

    /** Row lock serialises fills, expiry and compensation for the same order. */
    Optional<ReservationState> lockReservation(String orderId) {
        return queryReservation(
                "select * from buying_power_service.reservations where order_id = ? for update", orderId);
    }

    void applyToReservation(String orderId, LedgerDelta delta) {
        if (delta.reservationStatus() == null) {
            return;
        }
        jdbc.update(
                "update buying_power_service.reservations set released_amount_jpy = released_amount_jpy + ?,"
                        + " released_quantity = released_quantity + ?, status = ?, updated_at = now()"
                        + " where order_id = ?",
                delta.releasedAmountJpy(),
                delta.releasedQuantity(),
                delta.reservationStatus().name(),
                orderId);
    }

    /** Returns false when the account is unknown. */
    boolean applyToAccount(String accountId, LedgerDelta delta) {
        return jdbc.update(
                        "update buying_power_service.accounts set cash_jpy = cash_jpy + ?,"
                                + " reserved_jpy = reserved_jpy + ?, updated_at = now() where account_id = ?",
                        delta.cashDeltaJpy(), delta.reservedDeltaJpy(), accountId)
                == 1;
    }

    void applyToSellable(String accountId, String symbol, LedgerDelta delta) {
        if (delta.sellableQuantityDelta() == 0 && delta.sellableReservedDelta() == 0) {
            return;
        }
        int updated = jdbc.update(
                "update buying_power_service.sellable set quantity = quantity + ?,"
                        + " reserved_quantity = reserved_quantity + ?, updated_at = now()"
                        + " where account_id = ? and symbol = ?",
                delta.sellableQuantityDelta(), delta.sellableReservedDelta(), accountId, symbol);
        if (updated == 0 && delta.sellableQuantityDelta() > 0) {
            // First purchase of a symbol the account did not hold.
            jdbc.update(
                    "insert into buying_power_service.sellable (account_id, symbol, quantity, reserved_quantity)"
                            + " values (?, ?, ?, 0) on conflict (account_id, symbol) do update"
                            + " set quantity = buying_power_service.sellable.quantity + excluded.quantity,"
                            + " updated_at = now()",
                    accountId, symbol, delta.sellableQuantityDelta());
        }
    }

    void seedSellable(List<Object[]> rows) {
        jdbc.batchUpdate(
                "insert into buying_power_service.sellable (account_id, symbol, quantity, reserved_quantity)"
                        + " values (?, ?, ?, 0) on conflict do nothing",
                rows);
    }

    void seedAccounts(List<Object[]> rows) {
        jdbc.batchUpdate(
                "insert into buying_power_service.accounts (account_id, cash_jpy, reserved_jpy) values (?, ?, 0)"
                        + " on conflict do nothing",
                rows);
    }

    private Optional<ReservationState> queryReservation(String sql, String orderId) {
        return jdbc.query(sql, BuyingPowerRepository::mapReservation, orderId).stream().findFirst();
    }

    private static ReservationState mapReservation(ResultSet rs, int row) throws SQLException {
        return new ReservationState(
                rs.getString("order_id"),
                rs.getString("account_id"),
                rs.getString("symbol"),
                Side.valueOf(rs.getString("side")),
                rs.getString("order_type"),
                rs.getLong("quantity"),
                rs.getBigDecimal("reserved_amount_jpy"),
                rs.getBigDecimal("released_amount_jpy"),
                rs.getLong("reserved_quantity"),
                rs.getLong("released_quantity"),
                ReservationState.Status.valueOf(rs.getString("status")));
    }

    record AccountSnapshot(String accountId, BigDecimal cashJpy, BigDecimal reservedJpy, Instant updatedAt) {
        BigDecimal buyingPowerJpy() {
            return BuyingPowerRules.buyingPower(cashJpy, reservedJpy);
        }
    }

    record SellablePosition(String symbol, long quantity, long reservedQuantity) {
        long sellableQuantity() {
            return quantity - reservedQuantity;
        }
    }
}
