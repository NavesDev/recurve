package com.navesdev.recurve.shared.controller;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import lombok.RequiredArgsConstructor;

/**
 * Turns a {@code @Listing ListingRequest} controller argument into the
 * parsed listing parameters, so a feature's listing endpoint declares one
 * argument instead of the five the contract shares. Reads the raw query
 * parameters and hands them to {@link ListingRequests}; the rules stay
 * there. Registered by {@code shared/config/WebConfig}.
 */
@Component
@RequiredArgsConstructor
public class ListingRequestResolver implements HandlerMethodArgumentResolver {

    private static final String TEXT = "q";
    private static final String FILTER = "filter";
    private static final String PAGE = "page";
    private static final String SIZE = "size";
    private static final String SORT = "sort";

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    private final ListingRequests listings;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Listing.class)
                && ListingRequest.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {

        Listing listing = parameter.getParameterAnnotation(Listing.class);

        return listings.parse(
                request.getParameter(TEXT),
                // Raw values: binding would split a criterion on the comma
                // that separates its values.
                request.getParameterValues(FILTER),
                intOf(request, PAGE, DEFAULT_PAGE),
                intOf(request, SIZE, DEFAULT_SIZE),
                request.getParameter(SORT),
                listing.defaultSort());
    }

    private static int intOf(NativeWebRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("%s must be an integer, got '%s'".formatted(name, value));
        }
    }
}
