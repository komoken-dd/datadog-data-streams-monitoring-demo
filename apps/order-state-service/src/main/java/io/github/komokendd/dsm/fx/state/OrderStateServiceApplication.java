package io.github.komokendd.dsm.fx.state;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderStateServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderStateServiceApplication.class, args);
    }
}

