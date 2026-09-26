package com.payments.gateway.shared.web;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import com.payments.gateway.shared.error.GatewayException;

/** Lower-case wire representation of enums used in the public API. */
public final class WireEnums {

    private WireEnums() {
    }

    public static String wire(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value.trim())) {
                return constant;
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants()).map(WireEnums::wire).collect(Collectors.joining(", "));
        throw GatewayException.validation(field, "must be one of: " + allowed);
    }
}
