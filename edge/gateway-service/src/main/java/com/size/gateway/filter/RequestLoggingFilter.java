package com.size.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Records every gateway response, including requests that match no route. */
@Component
public class RequestLoggingFilter implements WebFilter, Ordered {

    private final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incomingId = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
        String requestId = incomingId == null || incomingId.isBlank()
                ? UUID.randomUUID().toString() : incomingId;
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();
        long start = System.nanoTime();
        ServerWebExchange forwarded = exchange.mutate().request(
                request -> request.headers(headers -> headers.set("X-Request-ID", requestId))
        ).build();

        // Register before routing: the outer exception handler also commits this response.
        // This observes the final 404/500 instead of the unset status at doFinally time.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set("X-Request-ID", requestId);
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            int code = status == null ? 200 : status.value();
            boolean failed = code >= 400;
            var event = failed ? log.atError() : log.atInfo();
            event.addKeyValue("event_action", "gateway_request_completed")
                    .addKeyValue("requestId", requestId)
                    .addKeyValue("method", method)
                    .addKeyValue("path", path)
                    .addKeyValue("status", code)
                    .addKeyValue("outcome", failed ? "error" : "success")
                    .addKeyValue("error_type", code >= 500 ? "server_error"
                            : failed ? "client_error" : "none")
                    .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                    .log("网关请求完成");
            return Mono.empty();
        });

        log.atInfo()
                .addKeyValue("event_action", "gateway_request_started")
                .addKeyValue("requestId", requestId)
                .addKeyValue("method", method)
                .addKeyValue("path", path)
                .log("网关请求开始");
        return chain.filter(forwarded);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
