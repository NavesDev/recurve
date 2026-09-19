package com.navesdev.recurve.shared.controller;

import java.time.Clock;
import java.util.List;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.navesdev.recurve.shared.domain.exception.BusinessRuleException;
import com.navesdev.recurve.shared.domain.exception.ExternalServiceException;
import com.navesdev.recurve.shared.domain.exception.NotFoundException;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;

import lombok.RequiredArgsConstructor;

/** Translates exceptions into {@link ApiError}, mapping by base type. */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Clock clock;

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ApiError> handleExternalService(ExternalServiceException e) {
        return respond(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException e) {
        return respond(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** No, wrong or refused credentials (BR-09); the message never says which. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleUnauthenticated(AuthenticationException e) {
        return respond(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        return respond(HttpStatus.FORBIDDEN, "Access denied");
    }

    /**
     * Fail-fast: a store that cannot be reached fails the request loudly.
     * The message is fixed — the exception names hosts and ports.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiError> handleStoreUnavailable(DataAccessResourceFailureException e) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "A backing service is unavailable");
    }

    /**
     * A search Elasticsearch refuses is a bad request, and its reason is
     * the client's to read: the index mapping is what says which fields
     * can be filtered or sorted on, so the refusal is the validation. Any
     * other status is the store's failure, not the caller's.
     */
    @ExceptionHandler(UncategorizedElasticsearchException.class)
    public ResponseEntity<ApiError> handleSearchRefused(UncategorizedElasticsearchException e) {
        if (e.getStatusCode() != null && e.getStatusCode() == HttpStatus.BAD_REQUEST.value()) {
            return respond(HttpStatus.BAD_REQUEST, reasonOf(e));
        }
        return respond(HttpStatus.BAD_GATEWAY, "The search engine failed to answer");
    }

    /** The innermost cause names the field; the outer one only says a query failed. */
    private static String reasonOf(UncategorizedElasticsearchException e) {
        if (e.getCause() instanceof ElasticsearchException es && es.error() != null) {
            ErrorCause cause = es.error().rootCause().isEmpty() ? es.error() : es.error().rootCause().getFirst();
            if (cause.reason() != null) {
                return cause.reason();
            }
        }
        return "The search engine refused the request";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        ApiError body = new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Request validation failed",
                clock.instant(),
                fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message, clock.instant()));
    }
}
