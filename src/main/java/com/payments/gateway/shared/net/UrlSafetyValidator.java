package com.payments.gateway.shared.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/**
 * SSRF guard for merchant-supplied URLs: requires HTTPS (unless disabled for local use) and rejects hosts that
 * resolve to loopback, private, link-local (incl. cloud metadata), CGNAT, multicast or unique-local addresses.
 */
public final class UrlSafetyValidator {

    private final boolean requireHttps;
    private final boolean allowPrivateTargets;

    public UrlSafetyValidator(boolean requireHttps, boolean allowPrivateTargets) {
        this.requireHttps = requireHttps;
        this.allowPrivateTargets = allowPrivateTargets;
    }

    /** Returns a reason when the URL is unsafe, empty when acceptable. */
    public Optional<String> check(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            return Optional.of("must be a URL of at most 2048 characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.of("is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && !requireHttps)) {
            return Optional.of(requireHttps ? "must use https" : "must use http or https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            return Optional.of("must have a host and no credentials");
        }
        if (allowPrivateTargets) {
            return Optional.empty();
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isInternal(address)) {
                    return Optional.of("must not resolve to a private or internal address");
                }
            }
        } catch (UnknownHostException e) {
            return Optional.of("host cannot be resolved");
        }
        return Optional.empty();
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second <= 127) || (first == 198 && (second == 18 || second == 19));
        }
        if (address instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
