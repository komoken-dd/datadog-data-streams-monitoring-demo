package io.github.komokendd.dsm.brokerage.buyingpower;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountQueryService {
    private final BuyingPowerRepository repository;

    AccountQueryService(BuyingPowerRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    Optional<AccountView> find(String accountId) {
        return repository.findAccount(accountId).map(account -> new AccountView(
                account.accountId(),
                account.cashJpy(),
                account.reservedJpy(),
                account.buyingPowerJpy(),
                repository.findSellable(accountId).stream()
                        .map(position -> new SellableView(
                                position.symbol(),
                                position.quantity(),
                                position.reservedQuantity(),
                                position.sellableQuantity()))
                        .toList(),
                account.updatedAt()));
    }

    record AccountView(
            String accountId,
            BigDecimal cashBalanceJpy,
            BigDecimal reservedAmountJpy,
            BigDecimal buyingPowerJpy,
            List<SellableView> sellable,
            Instant updatedAt) {
    }

    record SellableView(String symbol, long quantity, long reservedQuantity, long sellableQuantity) {
    }
}
