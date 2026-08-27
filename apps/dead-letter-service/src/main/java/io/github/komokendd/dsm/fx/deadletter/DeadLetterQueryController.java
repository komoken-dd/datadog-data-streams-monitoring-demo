package io.github.komokendd.dsm.fx.deadletter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo/dead-letters")
final class DeadLetterQueryController {
    private final DeadLetterRepository repository;

    DeadLetterQueryController(DeadLetterRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/unresolved")
    UnresolvedDeadLetters unresolved() {
        List<UnresolvedDeadLetter> rows = repository.findUnresolved();
        BigDecimal totalNotionalUsd = rows.stream()
                .map(UnresolvedDeadLetter::notionalUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new UnresolvedDeadLetters(rows.size(), totalNotionalUsd, Instant.now(), rows);
    }

    record UnresolvedDeadLetters(
            int count,
            BigDecimal totalNotionalUsd,
            Instant asOf,
            List<UnresolvedDeadLetter> orders) {
    }
}
