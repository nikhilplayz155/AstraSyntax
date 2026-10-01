package io.astra.security;

/**
 * The single enforcement point for script capabilities.
 *
 * <p>Every action marked security-sensitive asks the gate before doing anything, which
 * is what guarantees that a natural-language rule cannot perform something a
 * structured rule would be denied. Denials throw
 * {@link io.astra.runtime.ActionFailure} so the rule stops cleanly with a message.</p>
 */
public interface SecurityGate {

    /** The active policy. */
    SecurityPolicy policy();

    /** Verify that an action may run in this context. */
    void checkAction(String actionId, io.astra.runtime.ExecContext context);

    /** Verify a console command dispatch. */
    void checkConsoleCommand(String command, io.astra.runtime.ExecContext context);

    /** Verify a file access inside the plugin data folder. */
    void checkFileAccess(String relativePath, io.astra.runtime.ExecContext context);

    /** Verify an HTTP request target (also used for webhooks). */
    void checkHttp(String url, boolean webhook, io.astra.runtime.ExecContext context);

    /** Verify that a module may be loaded. */
    void checkModuleLoad(String moduleName, String sourcePath);

    /** True when the URL host is in the given allowlist. */
    boolean domainAllowed(String url, java.util.List<String> allowedDomains);
}
