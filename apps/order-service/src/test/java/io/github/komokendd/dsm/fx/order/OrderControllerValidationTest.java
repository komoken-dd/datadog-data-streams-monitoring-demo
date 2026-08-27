package io.github.komokendd.dsm.fx.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(OrderErrorHandler.class)
class OrderControllerValidationTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private KafkaTemplate<String, OrderReceivedEvent> kafka;

    @MockitoBean
    private MeterRegistry metrics;

    @Test
    void rejectsQuantityWithTooManyFractionDigits() throws Exception {
        assertRejected("USD/JPY", "100000.567", "148.250000");
    }

    @Test
    void rejectsQuantityThatExceedsAvroPrecision() throws Exception {
        assertRejected("USD/JPY", "1000000000000000000000", "148.250000");
    }

    @Test
    void rejectsLimitPriceWithTooManyFractionDigits() throws Exception {
        assertRejected("USD/JPY", "100000.00", "148.1234567");
    }

    @Test
    void rejectsQuantityWhoseConvertedNotionalExceedsAvroPrecision() throws Exception {
        assertRejected("GBP/USD", "99999999999999999.99", "1.250000");
    }

    private void assertRejected(String currencyPair, String quantity, String limitPrice) throws Exception {
        String request = """
                {
                  "currencyPair": "%s",
                  "side": "BUY",
                  "quantity": %s,
                  "limitPrice": %s
                }
                """.formatted(currencyPair, quantity, limitPrice);

        mvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER"));
    }
}
