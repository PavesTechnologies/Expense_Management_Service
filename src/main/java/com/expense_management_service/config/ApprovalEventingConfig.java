package com.expense_management_service.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for Approval Workflow domain events (§7.1). {@code spring-boot-starter-amqp}
 * auto-configures the {@code ConnectionFactory}/{@code RabbitTemplate} from {@code spring.rabbitmq.*}
 * properties (defaulting to localhost:5672/guest/guest if unset, same as any Spring Boot AMQP app) -
 * only the exchange/queue/binding topology and the JSON message converter are declared here.
 * <p>
 * Consumers (Notification/Audit) are explicitly out of scope for now (§7.2-§7.5, §8 deferred) - this
 * queue exists so the transport is real and durable from day one, even though nothing consumes it yet.
 */
@Configuration
public class ApprovalEventingConfig {

    public static final String EXCHANGE_NAME = "approval.events";
    public static final String QUEUE_NAME = "approval.events.queue";
    public static final String ROUTING_KEY = "approval.#";

    @Bean
    public TopicExchange approvalEventsExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue approvalEventsQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding approvalEventsBinding(Queue approvalEventsQueue, TopicExchange approvalEventsExchange) {
        return BindingBuilder.bind(approvalEventsQueue).to(approvalEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
