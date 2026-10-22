package com.expense_management_service.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for handing client-billable expenses to AR: one message per handoff on a durable queue AR
 * consumes. A dedicated exchange, not {@code approval.events}: these messages carry the full billing payload and
 * are tracked until the broker confirms them ({@code ArBillingDeliveryServiceImpl}), while approval events are
 * fire-and-forget notifications.
 */
@Configuration
public class ArBillingMessagingConfig {

    public static final String EXCHANGE_NAME = "xms.ar.billing";
    public static final String QUEUE_NAME = "xms.ar.billing.queue";
    public static final String ROUTING_KEY = "ar.billing.expense_handed_off";

    @Bean
    public TopicExchange arBillingExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue arBillingQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding arBillingBinding(Queue arBillingQueue, TopicExchange arBillingExchange) {
        return BindingBuilder.bind(arBillingQueue).to(arBillingExchange).with("ar.billing.#");
    }
}
