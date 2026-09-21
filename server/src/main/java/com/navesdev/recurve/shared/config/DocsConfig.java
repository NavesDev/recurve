package com.navesdev.recurve.shared.config;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the API contract and the Swagger UI over it, under {@code /docs}.
 *
 * <p>The contract is contract-first: {@code docs/openapi.yaml} is written
 * by hand and is the API's promise to a client; nothing is generated from
 * the code, and nothing in the code is generated from it. The tests are
 * what keep the two from drifting.
 *
 * <p>This class only exists when {@code recurve.docs.enabled} is on. Off,
 * {@code /docs/**} is not mapped: the request falls through to the
 * default {@code authenticated()} rule in {@code SecurityConfig} and a
 * stranger learns neither the contract nor that there is one.
 */
@Configuration
@ConditionalOnProperty(name = "recurve.docs.enabled", havingValue = "true")
public class DocsConfig implements WebMvcConfigurer {

    public static final String PATH = "/docs";

    /** The UI's own assets live in the webjar; the page and the contract in {@code docs/}. */
    private static final String CONTENT = "classpath:/docs/";
    private static final String WEBJAR_ENTRY = "classpath*:META-INF/resources/webjars/swagger-ui/*/swagger-ui.css";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(PATH + "/ui/**").addResourceLocations(swaggerUiLocation());
        registry.addResourceHandler(PATH + "/**").addResourceLocations(CONTENT);
    }

    /** {@code /docs/} rather than {@code /docs}: the page links its assets relatively. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(PATH, PATH + "/");
        registry.addViewController(PATH + "/").setViewName("forward:" + PATH + "/index.html");
    }

    /**
     * The webjar keeps its files under its version number. Finding the
     * directory at startup, rather than naming the version here, means
     * bumping the dependency is a change in the pom alone.
     */
    private static String swaggerUiLocation() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(WEBJAR_ENTRY);
            if (found.length != 1) {
                throw new IllegalStateException("Expected exactly one swagger-ui webjar on the classpath, found " + found.length);
            }
            String css = found[0].getURL().toString();
            return css.substring(0, css.lastIndexOf('/') + 1);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot locate the swagger-ui webjar", e);
        }
    }
}
