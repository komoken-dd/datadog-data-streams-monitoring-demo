package io.github.komokendd.dsm.brokerage.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        if (io.github.komokendd.dsm.brokerage.common.DatabaseMigrations.runIfRequested()) return;
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
