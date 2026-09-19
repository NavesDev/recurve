package com.navesdev.recurve.user.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.navesdev.recurve.user.repository.UserSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the operator search index at startup, with the analyzers and
 * mapping in {@code search/}, and fills it from the database when it did
 * not exist — the index's equivalent of a schema migration. An existing
 * index is left alone: a mapping change is a deliberate rebuild through
 * {@code POST /api/users/reindex}.
 *
 * <p>Fail-fast: if the node cannot be reached, the exception stops the
 * application from starting. A listing that silently answered from a
 * missing index would be worse than no application.
 *
 * <p>Runs with no authenticated operator, which needs nothing special:
 * permissions are checked at the HTTP boundary, and the server rebuilding
 * its own index is not a request. Ordered first so that the bootstrap
 * operator finds the index in place.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class UserIndexBootstrap implements ApplicationRunner {

    private final UserSearchRepository searchRepository;
    private final UserService service;

    @Override
    public void run(ApplicationArguments args) {
        if (searchRepository.indexExists()) {
            return;
        }

        long indexed = service.reindex();
        log.info("Operator search index created and populated with {} operators", indexed);
    }
}
