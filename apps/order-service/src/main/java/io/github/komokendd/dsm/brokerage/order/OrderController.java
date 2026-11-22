package io.github.komokendd.dsm.brokerage.order;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
final class OrderController {
    private final OrderIntakeService intake;

    OrderController(OrderIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping
    ResponseEntity<Object> create(@RequestBody CreateOrderRequest request) {
        return toResponse(intake.submit(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> unreadable(HttpMessageNotReadableException exception) {
        return toResponse(intake.invalid(null, "request body is not a valid order"));
    }

    static ResponseEntity<Object> toResponse(OrderResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
