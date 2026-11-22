package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.DemoAccounts;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Seeds the fictional accounts once; restarts keep the current balances. Runs during bean
 * initialisation (after Flyway) so accounts exist before the HTTP server and Kafka listeners start.
 */
@Component
class DemoAccountSeeder implements InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(DemoAccountSeeder.class);

    private final BuyingPowerRepository repository;

    DemoAccountSeeder(BuyingPowerRepository repository) {
        this.repository = repository;
    }

    @Override
    public void afterPropertiesSet() {
        List<Object[]> accounts = new ArrayList<>();
        List<Object[]> sellable = new ArrayList<>();
        for (var account : DemoAccounts.all()) {
            accounts.add(new Object[] {account.accountId(), account.initialCashJpy()});
            account.initialShares().forEach((symbol, quantity) ->
                    sellable.add(new Object[] {account.accountId(), symbol, quantity}));
        }
        repository.seedAccounts(accounts);
        repository.seedSellable(sellable);
        LOG.info("Demo accounts seeded, accounts={}, positions={}", accounts.size(), sellable.size());
    }
}
