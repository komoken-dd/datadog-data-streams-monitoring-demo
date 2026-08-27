package io.github.komokendd.dsm.fx.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank String currencyPair,
        @NotBlank String side,
        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 17, fraction = 2)
        BigDecimal quantity,
        @NotNull
        @DecimalMin("0.000001")
        @Digits(integer = 13, fraction = 6)
        BigDecimal limitPrice,
        String demoScenarioId) {
}
