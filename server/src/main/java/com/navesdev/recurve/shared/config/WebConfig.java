package com.navesdev.recurve.shared.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.navesdev.recurve.shared.controller.ListingRequestResolver;

import lombok.RequiredArgsConstructor;

/** Wires the controller-side pieces every feature shares into Spring MVC. */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ListingRequestResolver listingRequestResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(listingRequestResolver);
    }
}
