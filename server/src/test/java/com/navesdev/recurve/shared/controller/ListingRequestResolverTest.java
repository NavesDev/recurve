package com.navesdev.recurve.shared.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * The resolver only carries the raw parameters to {@link ListingRequests};
 * what it owns is which argument it answers for, the defaults, and that
 * a number that is not one is a 400 with the parameter's name.
 */
class ListingRequestResolverTest {

    private final ListingRequestResolver resolver = new ListingRequestResolver(new ListingRequests(100));

    private MethodParameter listing;
    private MethodParameter plainRequest;
    private MethodParameter unannotated;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        Method method = Endpoint.class.getMethod("search", ListingRequest.class, ListingRequest.class, String.class);
        listing = new MethodParameter(method, 0);
        unannotated = new MethodParameter(method, 1);
        plainRequest = new MethodParameter(method, 2);
    }

    @Test
    void answersOnlyForAnAnnotatedListingRequest() {
        assertThat(resolver.supportsParameter(listing)).isTrue();
        assertThat(resolver.supportsParameter(unannotated)).isFalse();
        assertThat(resolver.supportsParameter(plainRequest)).isFalse();
    }

    @Test
    void anEmptyQueryIsTheFirstPageOfTwentyByTheFeatureDefault() {
        ListingRequest parsed = resolve(new MockHttpServletRequest());

        assertThat(parsed.filter().text()).isNull();
        assertThat(parsed.pageable().getPageNumber()).isZero();
        assertThat(parsed.pageable().getPageSize()).isEqualTo(20);
        assertThat(parsed.pageable().getSort().getOrderFor("name.keyword")).isEqualTo(Sort.Order.asc("name.keyword"));
    }

    @Test
    void everyParameterReachesTheParserAsWritten() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("q", "ada");
        request.setParameter("filter", "active:true,false", "role:admin");
        request.setParameter("page", "2");
        request.setParameter("size", "5");
        request.setParameter("sort", "createdAt:desc");

        ListingRequest parsed = resolve(request);

        assertThat(parsed.filter().text()).isEqualTo("ada");
        assertThat(parsed.filter().valuesOf("active")).containsExactly("true", "false");
        assertThat(parsed.filter().valuesOf("role")).containsExactly("admin");
        assertThat(parsed.pageable().getPageNumber()).isEqualTo(2);
        assertThat(parsed.pageable().getPageSize()).isEqualTo(5);
        assertThat(parsed.pageable().getSort().getOrderFor("createdAt")).isEqualTo(Sort.Order.desc("createdAt"));
    }

    @Test
    void aNumberThatIsNotOneIsAValidationErrorNamingTheParameter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("size", "many");

        assertThatThrownBy(() -> resolve(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("size");
    }

    private ListingRequest resolve(MockHttpServletRequest request) {
        return (ListingRequest) resolver.resolveArgument(listing, null, new ServletWebRequest(request), null);
    }

    /** A controller method shaped like the ones the resolver serves. */
    public static class Endpoint {
        public void search(
                @Listing(defaultSort = "name.keyword") ListingRequest listing,
                ListingRequest unannotated,
                String plain) {
        }
    }
}
