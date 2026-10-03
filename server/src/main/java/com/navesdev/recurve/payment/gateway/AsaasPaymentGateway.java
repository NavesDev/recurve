package com.navesdev.recurve.payment.gateway;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.navesdev.recurve.payment.domain.Payment;
import com.navesdev.recurve.payment.domain.exception.PaymentGatewayException;
import com.navesdev.recurve.subscriber.domain.Subscriber;

/**
 * {@link PaymentGateway} over the Asaas REST API (v3). A charge is made
 * with {@code billingType UNDEFINED}: the customer chooses Pix, boleto or
 * card on Asaas's invoice page, and Recurve never handles card data.
 *
 * <p>Our ids travel as Asaas's {@code externalReference}: the subscriber's
 * on its customer, the payment's on its charge. That is what lets a resend
 * find a charge that reached Asaas before a crash, and the webhook find the
 * payment a charge belongs to.
 *
 * <p>Every failure — a refusal, a server error, no answer at all — becomes
 * a {@link PaymentGatewayException} naming the operation and the status.
 * Never the key, never the response body: both could end up in a log or
 * in a client's error message.
 */
public class AsaasPaymentGateway implements PaymentGateway {

    /** Asaas's charge statuses, as documented, onto the states Recurve acts on. */
    private static final Map<String, ChargeState> STATES = Map.of(
            "PENDING", ChargeState.PENDING,
            "AWAITING_RISK_ANALYSIS", ChargeState.PENDING,
            "RECEIVED", ChargeState.PAID,
            "CONFIRMED", ChargeState.PAID,
            "RECEIVED_IN_CASH", ChargeState.PAID,
            "OVERDUE", ChargeState.OVERDUE,
            "REFUNDED", ChargeState.REFUNDED);

    private final RestClient client;

    public AsaasPaymentGateway(RestClient.Builder builder, AsaasProperties properties) {
        this.client = builder
                .baseUrl(properties.apiUrl())
                .defaultHeader("access_token", properties.apiKey())
                .build();
    }

    @Override
    public String ensureCustomer(Subscriber subscriber) {
        if (subscriber.getGatewayCustomerId() != null) {
            return subscriber.getGatewayCustomerId();
        }
        NewCustomer body = new NewCustomer(subscriber.getName(), subscriber.getDocument(), subscriber.getEmail(),
                subscriber.getId().toString());
        return call("create customer", () -> client.post().uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Created.class))
                .id();
    }

    @Override
    public Charge createCharge(Payment payment, String customerId) {
        NewCharge body = new NewCharge(customerId, "UNDEFINED", payment.getAmount(),
                LocalDate.ofInstant(payment.getDueAt(), ZoneOffset.UTC), payment.getId().toString());
        Created created = call("create charge", () -> client.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Created.class));
        return new Charge(created.id(), created.invoiceUrl());
    }

    @Override
    public Optional<Charge> findCharge(UUID paymentId) {
        Found found = call("find charge", () -> client.get()
                .uri(uri -> uri.path("/payments").queryParam("externalReference", paymentId).build())
                .retrieve()
                .body(Found.class));
        return found.data().stream().findFirst().map(created -> new Charge(created.id(), created.invoiceUrl()));
    }

    @Override
    public ChargeState chargeState(String externalId) {
        Status found = call("read charge", () -> client.get().uri("/payments/{id}", externalId)
                .retrieve()
                .body(Status.class));
        return stateOf(found.status());
    }

    /** Any status not listed — a refund in progress, a chargeback — is {@link ChargeState#OTHER}. */
    private static ChargeState stateOf(String status) {
        return STATES.getOrDefault(status == null ? "" : status, ChargeState.OTHER);
    }

    @Override
    public void receiveInCash(String externalId, BigDecimal amount, LocalDate paidOn) {
        call("receive in cash", () -> client.post().uri("/payments/{id}/receiveInCash", externalId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CashReceipt(paidOn, amount))
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public void refund(String externalId) {
        call("refund charge", () -> client.post().uri("/payments/{id}/refund", externalId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .toBodilessEntity());
    }

    private static <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new PaymentGatewayException(
                    "Asaas refused to %s: HTTP %d".formatted(operation, e.getStatusCode().value()), e);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Asaas could not be reached to %s".formatted(operation), e);
        }
    }

    record NewCustomer(String name, String cpfCnpj, String email, String externalReference) {
    }

    record NewCharge(String customer, String billingType, BigDecimal value, LocalDate dueDate,
            String externalReference) {
    }

    record CashReceipt(LocalDate paymentDate, BigDecimal value) {
    }

    record Created(String id, String invoiceUrl) {
    }

    record Found(List<Created> data) {
    }

    record Status(String status) {
    }
}
