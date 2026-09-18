package com.navesdev.recurve.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time is injected, never read statically: entities receive an
 * {@code Instant} as a parameter and tests control the clock. NFR-05 puts
 * every timestamp in UTC.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
