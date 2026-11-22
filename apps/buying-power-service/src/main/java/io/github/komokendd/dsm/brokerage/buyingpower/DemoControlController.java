package io.github.komokendd.dsm.brokerage.buyingpower;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo")
final class DemoControlController {
    private static final Logger LOG = LoggerFactory.getLogger(DemoControlController.class);

    private final ReflectionControl control;

    DemoControlController(ReflectionControl control) {
        this.control = control;
    }

    @GetMapping("/status")
    ReflectionControl.Status status() {
        return control.status();
    }

    @PostMapping("/delay")
    ReflectionControl.Status delay() {
        var status = control.injectDelay();
        LOG.warn("Demo: buying-power reflection slowed to {} ms per record", status.delayMillis());
        return status;
    }

    @PostMapping("/recover")
    ReflectionControl.Status recover() {
        var status = control.recover();
        LOG.info("Demo: buying-power reflection recovered, delay {} ms", status.delayMillis());
        return status;
    }
}
