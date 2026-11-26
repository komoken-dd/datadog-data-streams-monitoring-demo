package io.github.komokendd.dsm.brokerage.holdings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HoldingsServiceApplication {
    public static void main(String[] args) {
        if (io.github.komokendd.dsm.brokerage.common.DatabaseMigrations.runIfRequested()) return;
        SpringApplication.run(HoldingsServiceApplication.class, args);
    }
}
