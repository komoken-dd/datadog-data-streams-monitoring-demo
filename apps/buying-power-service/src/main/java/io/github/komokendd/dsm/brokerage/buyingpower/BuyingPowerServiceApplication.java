package io.github.komokendd.dsm.brokerage.buyingpower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BuyingPowerServiceApplication {
    public static void main(String[] args) {
        if (io.github.komokendd.dsm.brokerage.common.DatabaseMigrations.runIfRequested()) return;
        SpringApplication.run(BuyingPowerServiceApplication.class, args);
    }
}
