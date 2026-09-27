package com.payments.gateway.provider.razorpay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** A local stand-in for api.razorpay.com: canned responses per "METHOD /path", and a record of every request. */
final class RazorpayStub implements AutoCloseable {

    record Recorded(String method, String path, String query, String authorization, String body) {
    }

    record Canned(int status, String body, long delayMillis) {
    }

    private final HttpServer server;
    private final Map<String, Canned> responses = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    RazorpayStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    void reset() {
        responses.clear();
        requests.clear();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    RazorpayStub on(String methodAndPath, int status, String body) {
        responses.put(methodAndPath, new Canned(status, body, 0));
        return this;
    }

    RazorpayStub slow(String methodAndPath, long delayMillis) {
        responses.put(methodAndPath, new Canned(200, "{}", delayMillis));
        return this;
    }

    List<Recorded> requests() {
        return requests;
    }

    Recorded last(String methodAndPath) {
        return requests.stream().filter(r -> (r.method() + " " + r.path()).equals(methodAndPath))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no request " + methodAndPath + " in " + requests));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(method, path, exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
        Canned canned = responses.getOrDefault(method + " " + path,
                new Canned(400, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The id provided does not exist\"}}", 0));
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
