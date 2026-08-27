package io.github.komokendd.dsm.fx.deadletter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeadLetterServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeadLetterServiceApplication.class, args);
    }
}

