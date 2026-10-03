package com.navesdev.recurve.payment.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Which gateway the payment service talks to, by
 * {@code recurve.payment.gateway}: {@code fake} unless told otherwise,
 * {@code asaas} for Asaas. The Asaas properties are bound — and checked —
 * only when Asaas is chosen.
 */
@Configuration
public class PaymentGatewayConfig {

    public static final String PROPERTY = "recurve.payment.gateway";

    @Configuration
    @ConditionalOnProperty(name = PROPERTY, havingValue = "asaas")
    @EnableConfigurationProperties(AsaasProperties.class)
    static class Asaas {

        @Bean
        PaymentGateway asaasPaymentGateway(AsaasProperties properties) {
            return new AsaasPaymentGateway(RestClient.builder(), properties);
        }
    }

    @Bean
    @ConditionalOnProperty(name = PROPERTY, havingValue = "fake", matchIfMissing = true)
    PaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }
}
