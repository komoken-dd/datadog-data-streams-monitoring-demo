package io.github.komokendd.dsm.brokerage.order;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrderControllerIdempotencyTest {
    @Test
    void rejectsMissingClientIdentityBeforeCreatingAnyDurableOrderOrReservation() throws Exception {
        var intake = mock(OrderIntakeService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OrderController(intake)).build();
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"accountId":"A00001","symbol":"D101","side":"BUY","orderType":"MARKET","quantity":100}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(intake);
    }
}
