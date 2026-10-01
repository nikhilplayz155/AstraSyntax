package io.astra.security;

import java.util.List;

/**
 * Immutable view of {@code security.yml}.
 *
 * <p>The policy is created once from the configuration and then consulted on every
 * execution path - structured scripts, natural-language rules, modules and packages.
 * There is deliberately no way to bypass it from a script.</p>
 *
 * @param allowConsoleCommands  scripts may dispatch console commands
 * @param allowFileAccess       scripts may read/write files inside the data folder
 * @param allowHttpRequests     scripts may perform HTTP requests
 * @param httpAllowedDomains    domain allowlist for HTTP (empty means "no domains")
 * @param webhooksEnabled       webhooks are enabled
 * @param webhookAllowedDomains domain allowlist for webhooks
 * @param allowRemoteInstall    packages may be installed from remote sources
 * @param requireSignatures     remote packages must carry a signature
 * @param requireTrustedModules modules must be on the trusted list
 * @param maxLoopIterations     iteration cap for {@code repeat} loops
 * @param maxTasksPerScript     scheduled task cap per script
 * @param maxHttpResponseSize   maximum accepted HTTP response size in bytes
 */
public record SecurityPolicy(boolean allowConsoleCommands, boolean allowFileAccess, boolean allowHttpRequests,
                             List<String> httpAllowedDomains, boolean webhooksEnabled,
                             List<String> webhookAllowedDomains, boolean allowRemoteInstall,
                             boolean requireSignatures, boolean requireTrustedModules, int maxLoopIterations,
                             int maxTasksPerScript, long maxHttpResponseSize) {

    public SecurityPolicy {
        httpAllowedDomains = httpAllowedDomains == null ? List.of() : List.copyOf(httpAllowedDomains);
        webhookAllowedDomains = webhookAllowedDomains == null ? List.of() : List.copyOf(webhookAllowedDomains);
    }

    /** A policy that denies everything risky (used by tests and as a safe fallback). */
    public static SecurityPolicy restrictive() {
        return new SecurityPolicy(false, false, false, List.of(), false, List.of(), false, true, true,
            10000, 1000, 2L * 1024 * 1024);
    }
}
