package io.astra.data;

import io.astra.runtime.Value;
import io.astra.runtime.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One persisted row of data plus the encoding rules shared by every backend.
 *
 * <p>Values are stored as {@code type:text} pairs rather than as loose strings: that is
 * what lets {@code coins} stay a whole number across a restart instead of silently
 * becoming the string {@code "5"}, which would break every comparison in
 * {@code security.yml}-approved scripts after a reload.</p>
 */
final class ValueCodec {

    static final char SEPARATOR = ':';

    private ValueCodec() { }

    /** Encode a value for storage. Non-persistable values become empty. */
    static String encode(Value value) {
        if (value == null || value.isNull() || !value.type().isPersistable()) return "";
        ValueType type = value.type();
        String text = switch (type) {
            case LIST -> {
                StringBuilder sb = new StringBuilder();
                for (Value element : value.asList()) {
                    if (sb.length() > 0) sb.append('\u0001');
                    sb.append(encode(element));
                }
                yield sb.toString();
            }
            case LOCATION -> {
                Value.Loc loc = (Value.Loc) value;
                yield loc.world() + " " + loc.x() + " " + loc.y() + " " + loc.z() + " " + loc.yaw() + " " + loc.pitch();
            }
            default -> value.asString();
        };
        return type.name() + SEPARATOR + text;
    }

    /** Decode a stored value; an empty string means "nothing stored". */
    static Value decode(String stored) {
        if (stored == null || stored.isEmpty()) return Value.NULL;
        int separator = stored.indexOf(SEPARATOR);
        if (separator <= 0) return Value.str(stored);
        String typeName = stored.substring(0, separator);
        String text = stored.substring(separator + 1);
        ValueType type;
        try {
            type = ValueType.valueOf(typeName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Value.str(stored);
        }
        if (!type.isPersistable()) return Value.NULL;
        return switch (type) {
            case STRING -> Value.str(text);
            case INT -> Value.num(parseLong(text, 0L));
            case DECIMAL -> Value.dec(parseDouble(text, 0d));
            case BOOLEAN -> Value.bool(Boolean.parseBoolean(text));
            case UUID -> {
                try {
                    yield Value.uuid(UUID.fromString(text));
                } catch (IllegalArgumentException broken) {
                    yield Value.NULL;
                }
            }
            case MATERIAL -> {
                int space = text.lastIndexOf(' ');
                if (space > 0) {
                    yield Value.material(text.substring(0, space), parseLong(text.substring(space + 1), 1L));
                }
                yield Value.material(text, 1L);
            }
            case LOCATION -> decodeLocation(text);
            case LIST -> decodeList(text);
            default -> Value.NULL;
        };
    }

    private static Value decodeList(String text) {
        List<Value> values = new ArrayList<>();
        for (String part : text.split("\u0001", -1)) {
            Value value = decode(part);
            if (!value.isNull()) values.add(value);
        }
        return Value.list(values);
    }

    private static Value decodeLocation(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 6) return Value.NULL;
        try {
            return Value.location(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (NumberFormatException broken) {
            return Value.NULL;
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Encode a whole holder's data. */
    static Map<String, String> encodeAll(Map<String, Value> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            String encoded = encode(entry.getValue());
            if (!encoded.isEmpty()) out.put(entry.getKey(), encoded);
        }
        return out;
    }

    /** Decode a whole holder's data. */
    static Map<String, Value> decodeAll(Map<String, String> stored) {
        Map<String, Value> out = new LinkedHashMap<>();
        if (stored == null) return out;
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            Value value = decode(entry.getValue());
            if (!value.isNull()) out.put(entry.getKey(), value);
        }
        return out;
    }
}
