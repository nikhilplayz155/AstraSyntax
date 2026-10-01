package io.astra.module;

import io.astra.logging.AstraLogger;
import io.astra.language.parser.Vocabulary;
import io.astra.runtime.Registries;
import io.astra.runtime.RuntimeServices;

import java.nio.file.Path;

/**
 * The API a compiled AstraSyntax module implements.
 *
 * <p>A module is a jar in {@code plugins/AstraSyntax/modules/} whose {@code module.yml}
 * names its main class. Modules extend the vocabulary: they may register actions,
 * conditions, expressions and event triggers, and everything they register becomes
 * immediately parseable - there is no second vocabulary to keep in sync, because the
 * parser reads the registries directly.</p>
 *
 * <p>Modules load <em>before</em> scripts, not after: a script that uses a module action
 * must find that action while it is being compiled, so {@code onLoad} always runs before
 * the first {@code .ar} file is parsed.</p>
 */
public interface AstraModule {

    /** The module name; defaults to the simple class name. */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Called once when the module is loaded.
     *
     * @param context the module's view of the runtime
     * @throws Exception when the module cannot start; the failure is isolated and reported
     */
    void onLoad(AstraModuleContext context) throws Exception;

    /** Called when the module is unloaded (server shutdown or {@code /astra reload}). */
    default void onUnload() {
    }

    /** What a module receives when it loads. */
    interface AstraModuleContext {

        /** The runtime services (scheduler, data, security, text, ...). */
        RuntimeServices services();

        /** The live registries: register actions and conditions here. */
        Registries registries();

        /** The parser's view of the vocabulary, after this module's registrations. */
        Vocabulary vocabulary();

        /** A private folder for this module's files. */
        Path dataFolder();

        /** The logger, already prefixed with the module name. */
        AstraLogger logger();
    }
}
