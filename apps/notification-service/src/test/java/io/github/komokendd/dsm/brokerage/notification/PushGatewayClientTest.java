package io.github.komokendd.dsm.brokerage.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class PushGatewayClientTest {
    private static final PushGateway.PushMessage MESSAGE = new PushGateway.PushMessage(
            "n-1", "A0001", "order-1", "EXECUTION", "D105 SELL 300 @ 9,500 executed");

    @Test
    void postsTheNotificationAndReadsTheProviderAnswer() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://push-gateway");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://push-gateway/push"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"notificationId":"n-1","accountId":"A0001","orderId":"order-1",
                         "notificationType":"EXECUTION","message":"D105 SELL 300 @ 9,500 executed"}
                        """))
                .andRespond(withSuccess("{\"notificationId\":\"n-1\",\"duplicate\":true}",
                        MediaType.APPLICATION_JSON));

        var response = new PushGatewayClient(builder.build()).push(MESSAGE);

        assertThat(response.duplicate()).isTrue();
        server.verify();
    }

    @Test
    void reportsServiceUnavailableAsProviderUnavailable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://push-gateway");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://push-gateway/push")).andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> new PushGatewayClient(builder.build()).push(MESSAGE))
                .isInstanceOfSatisfying(PushDeliveryException.class, failure ->
                        assertThat(failure.failureCategory()).isEqualTo(PushDeliveryException.UNAVAILABLE));
    }

    @Test
    void classifiesTimeoutsSeparatelyFromRefusedConnections() {
        assertThat(PushGatewayClient.classify(new ResourceAccessException("read", new SocketTimeoutException())))
                .isEqualTo(PushDeliveryException.TIMEOUT);
        assertThat(PushGatewayClient.classify(new ResourceAccessException("connect",
                        new HttpConnectTimeoutException("connect timed out"))))
                .isEqualTo(PushDeliveryException.TIMEOUT);
        assertThat(PushGatewayClient.classify(new ResourceAccessException("refused", new ConnectException())))
                .isEqualTo(PushDeliveryException.UNAVAILABLE);
    }
}
