package io.github.komokendd.dsm.brokerage.tradereport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TradeReportServiceApplication {
    public static void main(String[] args) {
        if (io.github.komokendd.dsm.brokerage.common.DatabaseMigrations.runIfRequested()) return;
        SpringApplication.run(TradeReportServiceApplication.class, args);
    }
}
