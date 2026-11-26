package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DemoApiSecurityTest {
    private static final String OPERATOR = "operator" + "a".repeat(57);
    private static final String SERVICE = "caller" + "b".repeat(58);

    @Test
    void healthRemainsPublicButMetricsAndControlsRequireAuthentication() throws Exception {
        var filter = new DemoApiSecurity("order-flow-simulator", digest(OPERATOR), null);
        assertThat(invoke(filter, "GET", "/actuator/health/readiness", null)).isEqualTo(200);
        assertThat(invoke(filter, "GET", "/actuator/metrics", null)).isEqualTo(401);
        assertThat(invoke(filter, "POST", "/internal/demo/stop", null)).isEqualTo(401);
        assertThat(invoke(filter, "POST", "/internal/demo/stop", OPERATOR)).isEqualTo(200);
        assertThat(invoke(filter, "POST", "/internal/demo/stop", SERVICE)).isEqualTo(401);
    }

    @Test
    void serviceCallerCannotUseOperatorControlsOrCrossItsPathBoundary() throws Exception {
        var filter = new DemoApiSecurity("exchange-gateway-simulator", digest(OPERATOR), digest(SERVICE));
        assertThat(invoke(filter, "GET", "/internal/market/quotes", SERVICE)).isEqualTo(200);
        assertThat(invoke(filter, "POST", "/internal/demo/opening-auction", SERVICE)).isEqualTo(200);
        assertThat(invoke(filter, "POST", "/internal/demo/reset", SERVICE)).isEqualTo(401);
        assertThat(invoke(filter, "GET", "/actuator/metrics", SERVICE)).isEqualTo(401);
        assertThat(invoke(filter, "POST", "/internal/demo/reset", OPERATOR)).isEqualTo(200);
    }

    @Test
    void orderAndPushCallersHaveOnlyTheirRequiredEndpoints() throws Exception {
        var order = new DemoApiSecurity("order-service", digest(OPERATOR), digest(SERVICE));
        assertThat(invoke(order, "POST", "/orders", SERVICE)).isEqualTo(200);
        assertThat(invoke(order, "GET", "/orders", SERVICE)).isEqualTo(401);
        var push = new DemoApiSecurity("push-gateway-simulator", digest(OPERATOR), digest(SERVICE));
        assertThat(invoke(push, "POST", "/push", SERVICE)).isEqualTo(200);
        assertThat(invoke(push, "POST", "/internal/demo/outage", SERVICE)).isEqualTo(401);
    }

    @Test
    void missingShortOrSharedTokensFailClosed() {
        assertThatThrownBy(() -> new DemoApiSecurity("order-service", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoApiSecurity("order-service", "password", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoApiSecurity("order-service", digest(OPERATOR), digest(OPERATOR))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leakedVerifierCannotBeUsedAsABearerCredential() throws Exception {
        var filter = new DemoApiSecurity("order-service", digest(OPERATOR), digest(SERVICE));
        assertThat(invoke(filter, "POST", "/orders", digest(OPERATOR))).isEqualTo(401);
        assertThat(invoke(filter, "POST", "/orders", digest(SERVICE))).isEqualTo(401);
    }

    @Test
    void simulatorReadIdentityCannotReserveReleaseOrInjectFailures() throws Exception {
        String readOnly = "readonly" + "c".repeat(56);
        var filter = new DemoApiSecurity("buying-power-service", digest(OPERATOR), digest(SERVICE), digest(readOnly));
        assertThat(invoke(filter, "GET", "/internal/buying-power/accounts/A00001", readOnly)).isEqualTo(200);
        assertThat(invoke(filter, "POST", "/internal/buying-power/reservations", readOnly)).isEqualTo(401);
        assertThat(invoke(filter, "POST", "/internal/buying-power/reservations/a-b/release", readOnly)).isEqualTo(401);
        assertThat(invoke(filter, "POST", "/internal/demo/delay", readOnly)).isEqualTo(401);
    }

    private static String digest(String token) {
        return java.util.HexFormat.of().formatHex(DemoApiSecurity.hash(token));
    }

    private int invoke(DemoApiSecurity filter, String method, String path, String token) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
