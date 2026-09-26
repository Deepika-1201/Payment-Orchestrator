package com.payments.gateway.shared.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** An IPv4 or IPv6 CIDR block, e.g. {@code 203.0.113.0/24} or {@code 2001:db8::/32}. */
public record CidrRange(byte[] network, int prefixLength) {

    public static CidrRange parse(String cidr) {
        String value = cidr.trim();
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        if (!address.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("not an IP address literal: " + cidr);
        }
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid CIDR " + cidr, e);
        }
        int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(value.substring(slash + 1));
        if (prefix < 0 || prefix > bytes.length * 8) {
            throw new IllegalArgumentException("invalid prefix length in " + cidr);
        }
        return new CidrRange(bytes, prefix);
    }

    public boolean contains(String ipLiteral) {
        byte[] candidate;
        try {
            if (!ipLiteral.matches("[0-9A-Fa-f:.]+")) {
                return false;
            }
            candidate = InetAddress.getByName(ipLiteral).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits) & 0xFF;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
