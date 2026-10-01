package io.astra.language.docs;

import io.astra.util.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Central documentation registry.
 *
 * <p>Every built-in event, action, condition and expression registers its metadata here
 * while the runtime registers the behaviour. That keeps the two in sync (both are
 * written in the same place) and gives the tooling a single source of truth for
 * {@code /astra explain}, {@code /astra info} and future editor support.</p>
 */
public final class DocRegistry {

    private final Map<String, DocEntry> entries = new LinkedHashMap<>();

    /** Register (or replace) an entry. */
    public void register(DocEntry entry) {
        if (entry == null || entry.id() == null) return;
        entries.put(entry.id().toLowerCase(Locale.ROOT), entry);
    }

    public DocEntry get(String id) {
        return id == null ? null : entries.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    /** All entries of a given kind. */
    public List<DocEntry> of(DocKind kind) {
        List<DocEntry> out = new ArrayList<>();
        for (DocEntry entry : entries.values()) {
            if (entry.kind() == kind) out.add(entry);
        }
        return out;
    }

    /** All registered ids. */
    public Collection<String> ids() {
        return entries.keySet();
    }

    /** Search by id fragment or description keyword; used by {@code /astra info <term>}. */
    public List<DocEntry> search(String term) {
        List<DocEntry> out = new ArrayList<>();
        if (Strings.isBlank(term)) return out;
        String lower = term.toLowerCase(Locale.ROOT);
        for (DocEntry entry : entries.values()) {
            if (entry.id().contains(lower)
                || entry.name().toLowerCase(Locale.ROOT).contains(lower)
                || entry.description().toLowerCase(Locale.ROOT).contains(lower)
                || entry.syntax().stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(lower))) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Suggested ids for a mistyped term. */
    public List<String> suggestions(String term, int limit) {
        return Strings.nearest(term, entries.keySet(), limit);
    }

    public int size() {
        return entries.size();
    }

    /** Render a documentation page as plain text (used by {@code /astra info}). */
    public List<String> render(DocEntry entry) {
        List<String> lines = new ArrayList<>();
        lines.add(entry.kind().label() + " " + entry.id() + " - " + entry.description());
        if (!entry.syntax().isEmpty()) {
            lines.add("Syntax:");
            for (String syntax : entry.syntax()) lines.add("    " + syntax);
        }
        if (!entry.arguments().isEmpty()) {
            lines.add("Arguments:");
            for (DocArgument argument : entry.arguments()) {
                lines.add("    " + argument.name() + " (" + argument.type() + (argument.optional() ? ", optional" : "")
                    + "): " + argument.description());
            }
        }
        if (!"void".equals(entry.returnType())) lines.add("Returns: " + entry.returnType());
        if (!entry.examples().isEmpty()) {
            lines.add("Examples:");
            for (String example : entry.examples()) lines.add("    " + example);
        }
        if (entry.securitySensitive()) lines.add("Security: gated by security.yml");
        lines.add("Since: " + entry.since());
        return lines;
    }
}
