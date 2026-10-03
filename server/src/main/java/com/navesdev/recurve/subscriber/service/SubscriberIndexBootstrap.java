package com.navesdev.recurve.subscriber.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.subscriber.repository.SubscriberSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the subscriber search index at startup, with the analyzers and
 * mapping in {@code search/}, and fills it from the database when it did
 * not exist. An existing index is left alone: a mapping change is a
 * deliberate rebuild through {@code POST /api/subscribers/reindex}.
 *
 * <p>Fail-fast: if the node cannot be reached, the application does not
 * start. Nothing at startup writes a subscriber, and the rebuild reads
 * plans from the database rather than from their index, so the order only
 * has to put it before the first request.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class SubscriberIndexBootstrap implements ApplicationRunner {

    private final SubscriberSearchRepository searchRepository;
    private final SubscriberService service;

    @Override
    public void run(ApplicationArguments args) {
        if (searchRepository.indexExists()) {
            return;
        }

        long indexed = service.reindex();
        log.info("Subscriber search index created and populated with {} subscribers", indexed);
    }
}
