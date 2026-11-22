package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;

interface OrderEventPublisher {
    /** Blocks until the broker acknowledges the record; throws on failure. */
    void publish(OrderAcceptedEvent event);
}
