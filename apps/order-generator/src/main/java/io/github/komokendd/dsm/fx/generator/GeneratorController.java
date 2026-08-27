package io.github.komokendd.dsm.fx.generator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo")
final class GeneratorController {
    private final GeneratorControl control;

    GeneratorController(GeneratorControl control) {
        this.control = control;
    }

    @GetMapping("/status")
    GeneratorControl.GeneratorStatus status() {
        return control.status();
    }

    @PostMapping("/baseline")
    GeneratorControl.GeneratorStatus baseline() {
        return control.baseline();
    }

    @PostMapping("/stop")
    GeneratorControl.GeneratorStatus stop() {
        return control.stop();
    }

    @PostMapping("/surge")
    GeneratorControl.GeneratorStatus surge() {
        return control.surge();
    }

    @PostMapping("/processing-failure")
    GeneratorControl.GeneratorStatus processingFailure() {
        return control.markNextTenFailures();
    }
}
