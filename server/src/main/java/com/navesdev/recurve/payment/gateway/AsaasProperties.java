package com.navesdev.recurve.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to reach Asaas (NFR-03: from the environment, never the
 * repository). Bound only when Asaas is the gateway, and checked when it
 * is: a gateway without credentials would fail every charge, so it fails
 * the startup instead.
 *
 * @param apiUrl the API root, the sandbox's by default
 * @param apiKey sent as the {@code access_token} header
 * @param webhookToken what Asaas sends back as {@code asaas-access-token} on every webhook call
 */
@ConfigurationProperties("recurve.payment.asaas")
public record AsaasProperties(String apiUrl, String apiKey, String webhookToken) {

    public AsaasProperties {
        require(apiUrl, "recurve.payment.asaas.api-url");
        require(apiKey, "recurve.payment.asaas.api-key");
        require(webhookToken, "recurve.payment.asaas.webhook-token");
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required when the payment gateway is asaas");
        }
    }

    /** Never prints the key or the token. */
    @Override
    public String toString() {
        return "AsaasProperties[apiUrl=" + apiUrl + "]";
    }
}
