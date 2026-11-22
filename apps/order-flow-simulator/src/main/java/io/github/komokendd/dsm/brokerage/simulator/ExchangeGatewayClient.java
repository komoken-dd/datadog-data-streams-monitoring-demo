package io.github.komokendd.dsm.brokerage.simulator;

import io.github.komokendd.dsm.brokerage.common.DemoScenarios;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Market data and demo controls of exchange-gateway-simulator. Failures surface as RestClientException. */
public class ExchangeGatewayClient {
    private static final ParameterizedTypeReference<List<MarketView.QuoteDto>> QUOTES = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;

    public ExchangeGatewayClient(RestClient client) {
        this.client = client;
    }

    public List<MarketView.QuoteDto> quotes() {
        List<MarketView.QuoteDto> quotes = client.get().uri("/internal/market/quotes").retrieve().body(QUOTES);
        return quotes == null ? List.of() : quotes;
    }

    public void startOpeningAuction(long preOpenSeconds) {
        client.post()
                .uri(builder -> builder.path("/internal/demo/opening-auction")
                        .queryParam("preOpenSeconds", preOpenSeconds)
                        .build())
                .retrieve()
                .toBodilessEntity();
    }

    public void startSellOff(long durationSeconds) {
        client.post()
                .uri(builder -> builder.path("/internal/demo/sell-off")
                        .queryParam("symbol", DemoScenarios.SELL_OFF_SYMBOL)
                        .queryParam("durationSeconds", durationSeconds)
                        .build())
                .retrieve()
                .toBodilessEntity();
    }
}
