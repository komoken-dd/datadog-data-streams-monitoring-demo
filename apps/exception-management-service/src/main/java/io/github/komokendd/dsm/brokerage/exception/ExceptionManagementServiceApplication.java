package io.github.komokendd.dsm.brokerage.exception;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExceptionManagementServiceApplication {
    public static void main(String[] args) {
        if (io.github.komokendd.dsm.brokerage.common.DatabaseMigrations.runIfRequested()) return;
        SpringApplication.run(ExceptionManagementServiceApplication.class, args);
    }
}
