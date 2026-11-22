package io.github.komokendd.dsm.brokerage.exception;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExceptionController {
    private final NotificationDeadLetterRepository repository;
    private final RedriveService redriveService;
    private final Clock clock;

    ExceptionController(NotificationDeadLetterRepository repository, RedriveService redriveService, Clock clock) {
        this.repository = repository;
        this.redriveService = redriveService;
        this.clock = clock;
    }

    @GetMapping("/internal/exceptions")
    UnresolvedExceptions unresolved() {
        List<NotificationDeadLetter> rows = repository.findUnresolved();
        return new UnresolvedExceptions(rows.size(), clock.instant(), rows);
    }

    @PostMapping("/internal/demo/redrive")
    RedriveService.RedriveResult redrive() throws Exception {
        return redriveService.redrive();
    }

    record UnresolvedExceptions(int count, Instant asOf, List<NotificationDeadLetter> exceptions) {
    }
}
