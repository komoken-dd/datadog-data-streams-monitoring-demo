package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Only books the order; matching happens on the scheduler so the listener stays fast during bursts. */
@Component
class AcceptedOrderListener {
    private static final Logger LOG = LoggerFactory.getLogger(AcceptedOrderListener.class);

    private final WorkingOrderRepository repository;

    AcceptedOrderListener(WorkingOrderRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(
            topics = Topics.ORDERS_ACCEPTED,
            groupId = "exchange-gateway-simulator-v1",
            containerFactory = "acceptedOrderListenerFactory")
    @Transactional
    void onOrderAccepted(OrderAcceptedEvent event) {
        boolean inserted = repository.insert(ExchangeEvents.workingOrder(event));
        try (var ignored = EventLogContext.open(
                event.getAccountId(), event.getOrderId(), event.getEventId(), event.getEventType(),
                event.getSymbol(), event.getDemoScenarioId())) {
            if (inserted) {
                LOG.info("Order booked on the exchange: {} {} {} shares",
                        event.getSide(), event.getOrderType(), event.getQuantity());
            } else {
                LOG.debug("Duplicate accepted-order event ignored");
            }
        }
    }
}
