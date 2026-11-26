package io.github.komokendd.dsm.brokerage.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DemoSecurityConfiguration {
    @Bean
    RestClientCustomizer demoServiceAuthentication(Environment environment) {
        String token = environment.getProperty("BROKERAGE_DEMO_CLIENT_TOKEN");
        if (token != null && !token.matches("[A-Za-z0-9_-]{43,128}")) {
            throw new IllegalArgumentException("Invalid service client token");
        }
        return builder -> {
            if (token != null) builder.defaultHeader("Authorization", "Bearer " + token);
        };
    }

    @Bean
    FilterRegistrationBean<DemoApiSecurity> demoApiSecurity(Environment environment) {
        var registration = new FilterRegistrationBean<>(new DemoApiSecurity(
                environment.getRequiredProperty("spring.application.name"),
                environment.getRequiredProperty("BROKERAGE_DEMO_OPERATOR_TOKEN_SHA256"),
                environment.getProperty("BROKERAGE_DEMO_ALLOWED_SERVICE_TOKEN_SHA256"),
                environment.getProperty("BROKERAGE_DEMO_READ_ONLY_TOKEN_SHA256")));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
