package io.github.komokendd.dsm.brokerage.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderFlowSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderFlowSimulatorApplication.class, args);
    }
}
