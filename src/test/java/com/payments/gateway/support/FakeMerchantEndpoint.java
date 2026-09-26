package com.payments.gateway.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stand-in for a merchant's webhook endpoint that records deliveries and returns a configurable status. */
public final class FakeMerchantEndpoint implements AutoCloseable {

    public record Received(Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    public FakeMerchantEndpoint() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/webhooks", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
                received.add(new Received(headers, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks";
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void respondWith(int statusCode) {
        this.status = statusCode;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
