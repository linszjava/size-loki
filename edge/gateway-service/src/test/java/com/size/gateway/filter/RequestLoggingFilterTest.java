package com.size.gateway.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.server.handler.ResponseStatusExceptionHandler;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLogs() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 302, 400, 404, 503})
    void classifiesDownstreamResponsesAndPreservesRequestId(int status) {
        var response = request(exchange -> {
            assertThat(exchange.getRequest().getHeaders().getFirst("X-Request-ID")).isEqualTo("test-request");
            exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
            return exchange.getResponse().setComplete();
        }, "/api/orders/1003", "test-request");
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isEqualTo("test-request");
        assertCompletion(status);
    }

    @Test
    void logsUnmatchedUrlAfterOuterExceptionHandlerSets404() {
        // WebFlux reports a missing handler as a ResponseStatusException outside the filter.
        var response = request(exchange -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)),
                "/no-such-url", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isNotBlank();
        assertCompletion(404);
        assertThat(fields(completion()).get("path")).isEqualTo("/no-such-url");
    }

    @Test
    void logsUnhandledFailureAs500() {
        var response = request(exchange -> Mono.error(new IllegalStateException("test failure")),
                "/api/orders/1001", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertCompletion(500);
    }

    @Test
    void defaultsImplicitSuccessfulResponseTo200() {
        request(exchange -> exchange.getResponse().setComplete(), "/ok", null);
        assertCompletion(200);
    }

    private MockServerHttpResponse request(WebHandler handler, String path, String requestId) {
        var httpHandler = WebHttpHandlerBuilder.webHandler(handler)
                .filter(new RequestLoggingFilter())
                .exceptionHandler(new ResponseStatusExceptionHandler()).build();
        var builder = MockServerHttpRequest.get(path);
        if (requestId != null) {
            builder.header("X-Request-ID", requestId);
        }
        var response = new MockServerHttpResponse();
        httpHandler.handle(builder.build(), response).block(Duration.ofSeconds(5));
        return response;
    }

    private ILoggingEvent completion() {
        var events = appender.list.stream()
                .filter(event -> "网关请求完成".equals(event.getMessage())).toList();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private void assertCompletion(int status) {
        var event = completion();
        assertThat(event.getLevel()).isEqualTo(status >= 400 ? Level.ERROR : Level.INFO);
        assertThat(fields(event)).containsEntry("status", status)
                .containsEntry("event_action", "gateway_request_completed")
                .containsEntry("outcome", status >= 400 ? "error" : "success")
                .containsEntry("error_type", status >= 500 ? "server_error"
                        : status >= 400 ? "client_error" : "none");
    }

    private Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
