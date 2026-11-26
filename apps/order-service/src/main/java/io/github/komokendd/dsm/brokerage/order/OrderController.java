package io.github.komokendd.dsm.brokerage.order;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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
    ResponseEntity<Object> create(@RequestBody CreateOrderRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return toResponse(intake.submit(request, idempotencyKey));
    }

    @GetMapping("/{orderId}")
    ResponseEntity<Object> status(@PathVariable("orderId") String orderId) {
        return intake.status(orderId).map(OrderController::toResponse).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Object> unreadable(HttpMessageNotReadableException exception) {
        return toResponse(intake.invalid(null, "request body is not a valid order"));
    }

    static ResponseEntity<Object> toResponse(OrderResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
