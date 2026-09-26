package com.payments.gateway.routing;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** One ANDed condition of a routing rule, e.g. {@code {"field":"amount","op":"gte","value":500000}}. */
public record RuleCondition(String field, String op, Object value) {

    public static final Set<String> FIELDS = Set.of("method", "upi_flow", "bank_code", "amount", "currency", "capture_method");
    public static final Set<String> OPERATORS = Set.of("eq", "neq", "in", "not_in", "gt", "gte", "lt", "lte");

    /** Returns a validation problem, empty when the condition is well-formed. */
    public Optional<String> validate() {
        if (!FIELDS.contains(field)) {
            return Optional.of("unknown field '" + field + "'");
        }
        if (!OPERATORS.contains(op)) {
            return Optional.of("unknown operator '" + op + "'");
        }
        if (value == null) {
            return Optional.of("value is required");
        }
        boolean numericOp = op.startsWith("g") || op.startsWith("l");
        if (numericOp && (!field.equals("amount") || !(value instanceof Number))) {
            return Optional.of("operator '" + op + "' requires field 'amount' and a numeric value");
        }
        if ((op.equals("in") || op.equals("not_in")) && !(value instanceof Collection<?>)) {
            return Optional.of("operator '" + op + "' requires a list value");
        }
        return Optional.empty();
    }

    public boolean matches(RoutingContext context) {
        Object actual = switch (field) {
            case "method" -> context.method().type().name();
            case "upi_flow" -> context.method().upiFlow() == null ? null : context.method().upiFlow().name();
            case "bank_code" -> context.method().bankCode();
            case "amount" -> context.amount().amount();
            case "currency" -> context.amount().currency();
            case "capture_method" -> context.captureMethod().name();
            default -> null;
        };
        if (actual == null) {
            return false;
        }
        return switch (op) {
            case "eq" -> same(actual, value);
            case "neq" -> !same(actual, value);
            case "in" -> value instanceof Collection<?> values && values.stream().anyMatch(v -> same(actual, v));
            case "not_in" -> value instanceof Collection<?> values && values.stream().noneMatch(v -> same(actual, v));
            case "gt", "gte", "lt", "lte" -> actual instanceof Number a && value instanceof Number v && compare(a.longValue(), v.longValue());
            default -> false;
        };
    }

    private boolean compare(long actual, long expected) {
        return switch (op) {
            case "gt" -> actual > expected;
            case "gte" -> actual >= expected;
            case "lt" -> actual < expected;
            default -> actual <= expected;
        };
    }

    private static boolean same(Object actual, Object expected) {
        if (expected == null) {
            return false;
        }
        if (actual instanceof Number a && expected instanceof Number e) {
            return a.longValue() == e.longValue();
        }
        return String.valueOf(actual).toUpperCase(Locale.ROOT).equals(String.valueOf(expected).toUpperCase(Locale.ROOT));
    }
}
