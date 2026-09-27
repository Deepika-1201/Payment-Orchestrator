package com.payments.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A local stand-in for a PSP's REST API (ADR-030, ADR-031): canned responses per "METHOD /path" and a record of every
 * request. Unconfigured paths answer {@code fallbackStatus} with {@code fallbackBody}, the PSP's "not found".
 */
public final class StubPsp implements AutoCloseable {

    /** Header names are lower case. */
    public record Recorded(String method, String path, String query, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String authorization() {
            return header("Authorization");
        }
    }

    private record Canned(int status, String body, long delayMillis) {
    }

    private final HttpServer server;
    private final String basePath;
    private final Canned fallback;
    private final Map<String, Canned> responses = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public StubPsp(String basePath, int fallbackStatus, String fallbackBody) {
        this.basePath = basePath;
        this.fallback = new Canned(fallbackStatus, fallbackBody, 0);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + basePath);
    }

    public void reset() {
        responses.clear();
        requests.clear();
    }

    public StubPsp on(String methodAndPath, int status, String body) {
        responses.put(methodAndPath, new Canned(status, body, 0));
        return this;
    }

    public StubPsp slow(String methodAndPath, long delayMillis) {
        responses.put(methodAndPath, new Canned(200, "{}", delayMillis));
        return this;
    }

    public List<Recorded> requests() {
        return requests;
    }

    public Recorded last(String methodAndPath) {
        return requests.stream().filter(r -> (r.method() + " " + r.path()).equals(methodAndPath))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no request " + methodAndPath + " in " + requests));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().toLowerCase(Locale.ROOT), e -> e.getValue().getFirst(),
                        (first, second) -> first));
        requests.add(new Recorded(method, path, exchange.getRequestURI().getRawQuery(), headers, body));
        Canned canned = responses.getOrDefault(method + " " + path, fallback);
        if (canned.delayMillis() > 0) {
            try {
                Thread.sleep(canned.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = canned.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(canned.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
