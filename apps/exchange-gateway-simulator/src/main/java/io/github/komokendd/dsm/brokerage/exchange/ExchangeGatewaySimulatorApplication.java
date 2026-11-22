package io.github.komokendd.dsm.brokerage.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExchangeGatewaySimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExchangeGatewaySimulatorApplication.class, args);
    }
}
