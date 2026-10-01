package io.astra.runtime.expression;

import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;
import io.astra.util.Strings;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Context-aware placeholder engine ({@code {player}}, {@code {world}}, {@code {health}},
 * {@code {coins}}, ...).
 *
 * <p>Two families are supported:</p>
 * <ul>
 *   <li><b>Astra placeholders</b> - {@code {name}} - resolved from the live execution
 *       context, including custom per-player data such as {@code {coins}}.</li>
 *   <li><b>External placeholders</b> - {@code %placeholder%} - delegated to an
 *       integration bridge (PlaceholderAPI) when one is installed and enabled.</li>
 * </ul>
 *
 * <p>A missing placeholder is left untouched rather than replaced with "null", because
 * scripts frequently embed text that merely looks like a placeholder.</p>
 */
public final class PlaceholderService {

    /** Resolves one Astra placeholder for a context. */
    @FunctionalInterface
    public interface Provider {
        Value resolve(String name, ExecContext context);
    }

    /** Bridge to an external placeholder implementation (PlaceholderAPI). */
    @FunctionalInterface
    public interface ExternalBridge {
        String resolve(String placeholder, ExecContext context);
    }

    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private volatile ExternalBridge externalBridge;

    /** Register or replace a placeholder provider. Names are case-insensitive. */
    public void register(String name, Provider provider) {
        if (name == null || provider == null) return;
        providers.put(name.toLowerCase(Locale.ROOT), provider);
    }

    public boolean has(String name) {
        return name != null && providers.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Install the external (PlaceholderAPI) bridge, or {@code null} to remove it. */
    public void setExternalBridge(ExternalBridge bridge) {
        this.externalBridge = bridge;
    }

    public boolean hasExternalBridge() {
        return externalBridge != null;
    }

    /** Resolve a single Astra placeholder to a value. */
    public Value value(String name, ExecContext context) {
        if (name == null) return Value.NULL;
        Provider provider = providers.get(name.toLowerCase(Locale.ROOT));
        if (provider == null) return Value.NULL;
        try {
            Value value = provider.resolve(name, context);
            return value == null ? Value.NULL : value;
        } catch (RuntimeException e) {
            return Value.NULL;
        }
    }

    /**
     * Expand every {@code {name}} and {@code %external%} placeholder inside a template.
     * Unknown placeholders are preserved verbatim.
     */
    public String resolve(String template, ExecContext context) {
        if (template == null || template.isEmpty()) return "";
        String result = Strings.isBlank(template) ? template : expandBraces(template, context);
        ExternalBridge bridge = externalBridge;
        if (bridge != null && result.indexOf('%') >= 0) {
            result = expandPercents(result, context, bridge);
        }
        return result;
    }

    private String expandBraces(String template, ExecContext context) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i);
            if (close < 0) {
                out.append(template.substring(i));
                break;
            }
            String name = template.substring(i + 1, close);
            Value value = value(name, context);
            if (value.isNull()) {
                out.append(template, i, close + 1);
            } else {
                out.append(value.asString());
            }
            i = close + 1;
        }
        return out.toString();
    }

    private String expandPercents(String template, ExecContext context, ExternalBridge bridge) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('%', i + 1);
            if (close < 0) {
                out.append(template.substring(i));
                break;
            }
            String placeholder = template.substring(i + 1, close);
            String resolved = bridge.resolve(placeholder, context);
            out.append(resolved == null ? template.substring(i, close + 1) : resolved);
            i = close + 1;
        }
        return out.toString();
    }

    /** All registered Astra placeholder names (used by {@code /astra info}). */
    public java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(providers.keySet());
    }
}
