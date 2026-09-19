package com.navesdev.recurve.shared.controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link ListingRequest} controller argument as the parsed listing
 * parameters of the request (FR-06, FR-07): {@code q}, {@code filter},
 * {@code page}, {@code size} and {@code sort}, resolved by
 * {@link ListingRequestResolver}. The feature only names its default sort
 * field; everything else is the contract every listing shares.
 *
 * <pre>
 * &#64;GetMapping
 * public PageResponse&lt;PlanResponse&gt; search(&#64;Listing(defaultSort = "name.keyword") ListingRequest listing)
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Listing {

    /** The field the feature lists by when {@code sort} is absent, named as its index names it. */
    String defaultSort();
}
