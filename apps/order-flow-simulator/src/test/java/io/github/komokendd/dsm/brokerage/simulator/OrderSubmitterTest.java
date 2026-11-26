package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import io.github.komokendd.dsm.brokerage.common.EventIds;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OrderSubmitterTest {
    @Test
    void preservesStableInquiryIdentityWhenTheResponseIsLost() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://order-service:8080");
        var server = MockRestServiceServer.bindTo(builder).build();
        var key = new AtomicReference<String>();
        server.expect(requestTo("http://order-service:8080/orders"))
                .andExpect(request -> key.set(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(request -> { throw new IOException("response lost"); });
        var result = new OrderSubmitter(builder.build()).submit(SwitchTradePlanTest.proposed().sell());
        assertThat(key.get()).isNotBlank();
        assertThat(result.accepted()).isFalse();
        assertThat(result.orderId()).isEqualTo(EventIds.derived("A00451:" + key.get(), "ORDER_ID"));
        server.verify();
    }

    @Test
    void readsAcceptedOrderIdentityAndCancelledInquiry() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://order-service:8080");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://order-service:8080/orders"))
                .andRespond(withSuccess("{\"orderId\":\"order-id\",\"status\":\"ACCEPTED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order-service:8080/orders/order-id"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .body("{\"code\":\"ORDER_CANCELLED\"}").contentType(MediaType.APPLICATION_JSON));
        var submitter = new OrderSubmitter(builder.build());
        assertThat(submitter.submit(SwitchTradePlanTest.proposed().sell()).orderId()).isEqualTo("order-id");
        assertThat(submitter.status("order-id")).contains("CANCELLED");
        server.verify();
    }
}
