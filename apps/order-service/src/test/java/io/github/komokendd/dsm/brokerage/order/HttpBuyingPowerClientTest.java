package io.github.komokendd.dsm.brokerage.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Rejected;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Reserved;
import io.github.komokendd.dsm.brokerage.order.BuyingPowerClient.Unavailable;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpBuyingPowerClientTest {
    private static final String BASE = "http://buying-power-service:8080";
    private static final BuyingPowerReservation.Request REQUEST = new BuyingPowerReservation.Request(
            "A00001", "o-1", "D101", "BUY", "LIMIT", 100, new BigDecimal("2800.0"));

    private MockRestServiceServer server;
    private HttpBuyingPowerClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpBuyingPowerClient(builder.build());
    }

    @Test
    void okIsReserved() {
        server.expect(requestTo(BASE + BuyingPowerReservation.RESERVATIONS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"orderId":"o-1","status":"RESERVED","reason":null,"reservedAmountJpy":280000,"buyingPowerJpy":29720000}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.reserve(REQUEST)).isInstanceOfSatisfying(Reserved.class,
                reserved -> assertThat(reserved.reservedAmountJpy()).isEqualByComparingTo("280000"));
    }

    @Test
    void unprocessableIsRejectedWithReason() {
        server.expect(requestTo(BASE + BuyingPowerReservation.RESERVATIONS_PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body("""
                        {"orderId":"o-1","status":"REJECTED","reason":"INSUFFICIENT_BUYING_POWER","reservedAmountJpy":0,"buyingPowerJpy":0}
                        """));

        assertThat(client.reserve(REQUEST)).isEqualTo(new Rejected("INSUFFICIENT_BUYING_POWER"));
    }

    @Test
    void unexpectedRejectReasonIsBounded() {
        server.expect(requestTo(BASE + BuyingPowerReservation.RESERVATIONS_PATH))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body("""
                        {"orderId":"o-1","status":"REJECTED","reason":"something-new"}
                        """));

        assertThat(client.reserve(REQUEST)).isEqualTo(new Rejected(HttpBuyingPowerClient.FALLBACK_REJECT_REASON));
    }

    @Test
    void serverErrorIsUnavailable() {
        server.expect(requestTo(BASE + BuyingPowerReservation.RESERVATIONS_PATH)).andRespond(withServerError());

        assertThat(client.reserve(REQUEST)).isInstanceOf(Unavailable.class);
    }

    @Test
    void timeoutIsUnavailable() {
        server.expect(requestTo(BASE + BuyingPowerReservation.RESERVATIONS_PATH)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        assertThat(client.reserve(REQUEST)).isInstanceOf(Unavailable.class);
    }

    @Test
    void failedCancellationRemainsUnconfirmed() {
        server.expect(requestTo(BASE + BuyingPowerReservation.RESERVATIONS_PATH + "/o-1/release"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThat(client.cancel("o-1")).isFalse();

        server.verify();
    }
}
