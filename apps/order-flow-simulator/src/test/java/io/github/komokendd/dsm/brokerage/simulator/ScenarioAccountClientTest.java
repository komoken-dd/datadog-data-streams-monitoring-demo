package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class ScenarioAccountClientTest {
    @Test
    void readsAccountAndAllExecutionsFromFullInquiryJson() {
        var builder = RestClient.builder().baseUrl("http://inquiry");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://inquiry/internal/buying-power/accounts/A00451"))
                .andRespond(withSuccess("""
                        {"accountId":"A00451","cashBalanceJpy":10000,"reservedAmountJpy":0,
                         "buyingPowerJpy":10000,"updatedAt":"2026-11-25T00:00:00Z",
                         "sellable":[{"symbol":"7203","quantity":200,"reservedQuantity":0,"sellableQuantity":200}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://inquiry/internal/orders/order-1"))
                .andRespond(withSuccess("""
                        {"orderId":"order-1","accountId":"A00451","side":"SELL","quantity":200,
                         "cumulativeQuantity":200,"status":"FILLED","acceptedAt":"2026-11-25T00:00:00Z",
                         "executions":[{"executionId":"fill-1","quantity":100,"price":1500.25,"sequence":1},
                                       {"executionId":"fill-2","quantity":100,"price":1501.50,"sequence":2}]}
                        """, MediaType.APPLICATION_JSON));
        var client = new ScenarioAccountClient(builder.build(), builder.build());
        var account = client.account("A00451").orElseThrow();
        assertThat(account.busy()).isFalse();
        assertThat(account.buyingPowerJpy()).isEqualByComparingTo("10000");
        var order = client.order("order-1").orElseThrow();
        assertThat(order.fullyObservedFill()).isTrue();
        assertThat(order.proceeds()).isEqualByComparingTo("300175");
        server.verify();
    }

    @Test
    void treatsMissingOrderAsPendingButFailsOnUnauthorizedInquiry() {
        var builder = RestClient.builder().baseUrl("http://inquiry");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://inquiry/internal/orders/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://inquiry/internal/orders/forbidden"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        var client = new ScenarioAccountClient(builder.build(), builder.build());
        assertThat(client.order("missing")).isEmpty();
        assertThatThrownBy(() -> client.order("forbidden")).isInstanceOf(RestClientException.class);
        server.verify();
    }
}
