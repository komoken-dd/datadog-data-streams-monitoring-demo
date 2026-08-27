package io.github.komokendd.dsm.fx.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableScheduling
public class OrderGeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderGeneratorApplication.class, args);
    }

    @org.springframework.context.annotation.Bean
    RestClient orderClient(RestClient.Builder builder, GeneratorProperties properties) {
        return builder.baseUrl(properties.orderServiceUrl()).build();
    }
}

