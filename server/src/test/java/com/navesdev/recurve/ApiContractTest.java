package com.navesdev.recurve;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.navesdev.recurve.shared.controller.ListingRequests;
import com.navesdev.recurve.user.controller.UserController;
import com.navesdev.recurve.user.service.UserService;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * The contract is written by hand, so nothing stops it from describing a
 * route that does not exist or missing one that does. This test does:
 * the document must be valid OpenAPI, and the routes it names must be
 * exactly the routes the controllers map.
 */
@WebMvcTest(controllers = UserController.class)
class ApiContractTest {

    private static final String CONTRACT = "docs/openapi.yaml";

    private static OpenAPI contract;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private ListingRequests listingRequests;

    /** Only there so the advice the slice pulls in can be built. */
    @MockitoBean
    private Clock clock;

    @BeforeAll
    static void parseContract() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(CONTRACT, null, options);

        assertThat(result.getMessages()).as("parser messages").isEmpty();
        assertThat(result.getOpenAPI()).as("parsed contract").isNotNull();
        contract = result.getOpenAPI();
    }

    @Test
    @DisplayName("every route the controllers map is in the contract, and only those")
    void routesMatch() {
        assertThat(documentedRoutes()).isEqualTo(mappedRoutes());
    }

    @Test
    @DisplayName("every operation has an id, a summary and at least one response")
    void operationsAreDescribed() {
        contract.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            String route = method + " " + path;
            assertThat(operation.getOperationId()).as("operationId of " + route).isNotBlank();
            assertThat(operation.getSummary()).as("summary of " + route).isNotBlank();
            assertThat(operation.getResponses()).as("responses of " + route).isNotEmpty();
        }));
    }

    private static Set<String> documentedRoutes() {
        Set<String> routes = new TreeSet<>();
        contract.getPaths().forEach((path, item) -> item.readOperationsMap()
                .keySet()
                .forEach(method -> routes.add(method + " " + path)));
        return routes;
    }

    private Set<String> mappedRoutes() {
        Set<String> routes = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, ?> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            for (var method : info.getMethodsCondition().getMethods()) {
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    routes.add(PathItem.HttpMethod.valueOf(method.name()) + " " + pattern);
                }
            }
        }
        return routes;
    }
}
