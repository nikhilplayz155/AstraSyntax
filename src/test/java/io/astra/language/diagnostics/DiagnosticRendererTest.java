package io.astra.language.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's diagnostics requirement: file, line, column, source excerpt, category,
 * explanation and suggestion have to survive all the way to the rendered block that a
 * server administrator reads in the console or in {@code /astra errors}.
 */
class DiagnosticRendererTest {

    private static final String SOURCE = """
        on player join:
            give player 5 dimonds
        """;

    private Diagnostic typo() {
        return new Diagnostic(Severity.ERROR, "material", "Unknown item: dimonds", "welcome.ar", 2, 18, 7,
            "The item name is not a Minecraft material or a known alias.", List.of("diamonds"),
            "    give player 5 diamonds");
    }

    @Test
    void renderedBlockCarriesEveryRequiredField() {
        String rendered = new DiagnosticRenderer(true, true).render(typo(), SOURCE);
        assertTrue(rendered.contains("File: welcome.ar"), rendered);
        assertTrue(rendered.contains("Line: 2"), rendered);
        assertTrue(rendered.contains("give player 5 dimonds"), "the source excerpt must be shown: " + rendered);
        assertTrue(rendered.contains("^^^^^^^"), "the offending token must be underlined: " + rendered);
        assertTrue(rendered.contains("Unknown item: dimonds"), rendered);
        assertTrue(rendered.contains("not a Minecraft material"), "the explanation must be shown: " + rendered);
        assertTrue(rendered.contains("diamonds"), "the suggestion must be shown: " + rendered);
        assertTrue(rendered.contains("give player 5 diamonds"), "the corrected line must be shown: " + rendered);
    }

    @Test
    void theCaretPointsAtTheReportedColumn() {
        String rendered = new DiagnosticRenderer(true, true).render(typo(), SOURCE);
        // The renderer indents the excerpt by four spaces and then reproduces the line
        // exactly, so the caret column lines up with the reported column.
        String sourceLine = DiagnosticRenderer.sourceLine(SOURCE, 2);
        String excerpt = "    " + sourceLine;
        String caretLine = " ".repeat(4 + 17) + "^^^^^^^";
        List<String> lines = rendered.lines().toList();
        assertTrue(lines.contains(excerpt), "excerpt line '" + excerpt + "' missing in: " + rendered);
        assertTrue(lines.contains(caretLine), "caret line '" + caretLine + "' missing in: " + rendered);
    }

    @Test
    void compactFormKeepsSeverityCategoryAndPosition() {
        assertEquals("[ERROR] material: Unknown item: dimonds (welcome.ar:2:18)",
            DiagnosticRenderer.compact(typo()));
    }

    @Test
    void sourceLineIsOneBasedAndTolerantOfMissingInput() {
        assertEquals("on player join:", DiagnosticRenderer.sourceLine(SOURCE, 1));
        assertEquals("    give player 5 dimonds", DiagnosticRenderer.sourceLine(SOURCE, 2));
        assertEquals("", DiagnosticRenderer.sourceLine(SOURCE, 3));
        assertEquals(null, DiagnosticRenderer.sourceLine(null, 2));
        assertEquals(null, DiagnosticRenderer.sourceLine(SOURCE, 0));
    }

    @Test
    void suggestionsCanBeSuppressedForQuietConsoles() {
        String quiet = new DiagnosticRenderer(false, false).render(typo(), SOURCE);
        assertTrue(quiet.contains("Unknown item: dimonds"));
        assertTrue(!quiet.contains("Did you mean"), quiet);
        assertTrue(!quiet.contains("not a Minecraft material"), quiet);
    }
}
