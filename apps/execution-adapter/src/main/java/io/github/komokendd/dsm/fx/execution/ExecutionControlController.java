package io.github.komokendd.dsm.fx.execution;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo")
final class ExecutionControlController {
    private final ExecutionControl control;

    ExecutionControlController(ExecutionControl control) {
        this.control = control;
    }

    @GetMapping("/status")
    ExecutionControl.Status status() {
        return control.status();
    }

    @PostMapping("/delay")
    ExecutionControl.Status delay() {
        return control.injectDelay();
    }

    @PostMapping("/recover")
    ExecutionControl.Status recover() {
        return control.recover();
    }
}

