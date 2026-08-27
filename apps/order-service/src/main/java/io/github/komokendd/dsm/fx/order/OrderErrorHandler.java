package io.github.komokendd.dsm.fx.order;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class OrderErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse badRequest(Exception exception) {
        return new ErrorResponse("INVALID_ORDER", exception.getMessage(), Instant.now());
    }

    record ErrorResponse(String code, String message, Instant occurredAt) {
    }
}

