package com.navesdev.recurve.payment.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.payment.repository.PaymentSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the payment search index at startup, with the analyzers and
 * mapping in {@code search/}, and fills it from the database when it did
 * not exist. An existing index is left alone: a mapping change is a
 * deliberate rebuild through {@code POST /api/payments/reindex}.
 *
 * <p>Fail-fast: if the node cannot be reached, the application does not
 * start. Nothing at startup writes a payment, and the rebuild reads
 * only the database, so the order only
 * has to put it before the first request.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class PaymentIndexBootstrap implements ApplicationRunner {

    private final PaymentSearchRepository searchRepository;
    private final PaymentService service;

    @Override
    public void run(ApplicationArguments args) {
        if (searchRepository.indexExists()) {
            return;
        }

        long indexed = service.reindex();
        log.info("Payment search index created and populated with {} payments", indexed);
    }
}
