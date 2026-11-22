package io.github.komokendd.dsm.brokerage.pushgateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/demo")
class DemoControlController {
    private final PushGatewayService service;

    DemoControlController(PushGatewayService service) {
        this.service = service;
    }

    @GetMapping("/status")
    PushGatewayService.Status status() {
        return service.status();
    }

    @PostMapping("/outage")
    PushGatewayService.Status outage() {
        return service.outage();
    }

    @PostMapping("/recover")
    PushGatewayService.Status recover() {
        return service.recover();
    }
}
