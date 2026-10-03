package com.navesdev.recurve.plan.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.plan.repository.PlanSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the plan search index at startup, with the analyzers and
 * mapping in {@code search/}, and fills it from the database when it did
 * not exist. An existing index is left alone: a mapping change is a
 * deliberate rebuild through {@code POST /api/plans/reindex}.
 *
 * <p>Fail-fast: if the node cannot be reached, the application does not
 * start. Nothing at startup writes a plan, so the order only has to put it
 * before the first request.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class PlanIndexBootstrap implements ApplicationRunner {

    private final PlanSearchRepository searchRepository;
    private final PlanService service;

    @Override
    public void run(ApplicationArguments args) {
        if (searchRepository.indexExists()) {
            return;
        }

        long indexed = service.reindex();
        log.info("Plan search index created and populated with {} plans", indexed);
    }
}
