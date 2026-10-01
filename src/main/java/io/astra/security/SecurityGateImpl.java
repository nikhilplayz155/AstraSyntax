package io.astra.security;

import io.astra.config.ConfigManager;
import io.astra.logging.AstraLogger;
import io.astra.runtime.ActionFailure;
import io.astra.runtime.ExecContext;
import io.astra.util.Strings;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The enforcement point for {@code security.yml}.
 *
 * <p>Every denial throws {@link ActionFailure}. That matters more than it looks: because
 * the gate is consulted by the action executor and never by a specific front end, a rule
 * written in English and a rule written in the structured syntax are checked by exactly
 * the same code, and neither can reach the console, the filesystem or the network without
 * passing through here.</p>
 *
 * <p>Policy comes from the supplied {@code security.yml}; the defaults below are only used
 * when a file is missing, and they are the restrictive ones.</p>
 */
public final class SecurityGateImpl implements SecurityGate {

    /** Permissions that override a policy denial (used for script authors/administrators). */
    public static final String BYPASS_PERMISSION = "astra.security.bypass";

    private final ConfigManager config;
    private final AstraLogger logger;
    private final Path dataFolder;
    private final Set<String> deniedOnce = ConcurrentHashMap.newKeySet();

    public SecurityGateImpl(ConfigManager config, AstraLogger logger, Path dataFolder) {
        this.config = config;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    @Override
    public SecurityPolicy policy() {
        if (config == null) return SecurityPolicy.restrictive();
        var scripts = config.security().scripts();
        var webhooks = config.security().webhooks();
        var packages = config.security().packages();
        var modules = config.security().modules();
        var limits = config.security().limits();
        return new SecurityPolicy(
            scripts.allowConsoleCommands(),
            scripts.allowFileAccess(),
            scripts.allowHttpRequests(),
            config.security().httpAllowedDomains(),
            webhooks.enabled(),
            webhooks.allowedDomains(),
            packages.allowRemoteInstall(),
            packages.requireSignatures(),
            modules.requireTrustedModules(),
            limits.maxLoopIterations(),
            limits.maxTasksPerScript(),
            limits.maxHttpResponseSize());
    }

    @Override
    public void checkAction(String actionId, ExecContext context) {
        if (actionId == null) return;
        SecurityPolicy policy = policy();
        switch (actionId) {
            case "run-console", "console-command", "run-as-console" -> {
                if (!policy.allowConsoleCommands()) {
                    deny("console command", "scripts.allow-console-commands is false in security.yml", context);
                }
            }
            case "read-file", "write-file", "delete-file" -> {
                if (!policy.allowFileAccess()) {
                    deny("file access", "scripts.allow-file-access is false in security.yml", context);
                }
            }
            case "http-request", "fetch", "webhook" -> {
                if (actionId.equals("webhook") || actionId.startsWith("webhook")) {
                    if (!policy.webhooksEnabled()) {
                        deny("webhook", "webhooks.enabled is false in security.yml", context);
                    }
                } else if (!policy.allowHttpRequests()) {
                    deny("http request", "scripts.allow-http-requests is false in security.yml", context);
                }
            }
            default -> {
                // Not a security-sensitive capability; nothing to enforce.
            }
        }
    }

    @Override
    public void checkConsoleCommand(String command, ExecContext context) {
        if (Strings.isBlank(command)) return;
        if (!policy().allowConsoleCommands()) {
            deny("console command", "'" + quote(command) + "' was blocked because "
                + "security.yml: scripts.allow-console-commands is false", context);
        }
    }

    @Override
    public void checkFileAccess(String relativePath, ExecContext context) {
        if (Strings.isBlank(relativePath)) return;
        if (!policy().allowFileAccess()) {
            deny("file access", "'" + quote(relativePath) + "' was blocked because "
                + "security.yml: scripts.allow-file-access is false", context);
        }
        // Even when allowed, a script may never escape the plugin data folder.
        if (escapesDataFolder(relativePath)) {
            deny("file access", "'" + quote(relativePath) + "' resolves outside the AstraSyntax data folder",
                context);
        }
    }

    @Override
    public void checkHttp(String url, boolean webhook, ExecContext context) {
        if (Strings.isBlank(url)) return;
        SecurityPolicy policy = policy();
        if (webhook) {
            if (!policy.webhooksEnabled()) {
                deny("webhook", "webhooks.enabled is false in security.yml", context);
            }
            if (!domainAllowed(url, policy.webhookAllowedDomains())) {
                deny("webhook", "the domain of '" + quote(url) + "' is not in webhooks.allowed-domains", context);
            }
            return;
        }
        if (!policy.allowHttpRequests()) {
            deny("http request", "scripts.allow-http-requests is false in security.yml", context);
        }
        if (policy.httpAllowedDomains().isEmpty()) {
            deny("http request", "http.allowed-domains is empty in security.yml, so no request can be made",
                context);
        }
        if (!domainAllowed(url, policy.httpAllowedDomains())) {
            deny("http request", "the domain of '" + quote(url) + "' is not in http.allowed-domains", context);
        }
    }

    @Override
    public void checkModuleLoad(String moduleName, String sourcePath) {
        SecurityPolicy policy = policy();
        if (!policy.requireTrustedModules()) return;
        if (moduleName == null) return;
        List<String> trusted = trustedModules();
        if (!trusted.isEmpty()) {
            if (trusted.contains(moduleName.toLowerCase(Locale.ROOT))) return;
            throw new ActionFailure("Module '" + moduleName + "' is not trusted (security.yml: "
                + "modules.require-trusted-modules is true, so a module must be listed in "
                + "modules.trusted-modules)");
        }
        // security.yml does not define a trusted list, so the honest rule is location based:
        // a module ships inside the server's own data folder or it is refused. Nothing that
        // was downloaded or referenced from outside the plugin folder is ever loaded.
        if (sourcePath != null && dataFolder != null) {
            try {
                Path source = Path.of(sourcePath).toAbsolutePath().normalize();
                if (source.startsWith(dataFolder.toAbsolutePath().normalize())) return;
            } catch (RuntimeException ignored) {
                // fall through to the denial below
            }
        }
        throw new ActionFailure("Module '" + moduleName + "' is not trusted (security.yml: "
            + "modules.require-trusted-modules is true and '" + (sourcePath == null ? "?" : sourcePath)
            + "' is outside the AstraSyntax data folder)");
    }

    @Override
    public boolean domainAllowed(String url, List<String> allowedDomains) {
        String host = hostOf(url);
        if (host == null) return false;
        if (allowedDomains == null || allowedDomains.isEmpty()) return false;
        for (String allowed : allowedDomains) {
            if (Strings.isBlank(allowed)) continue;
            String pattern = allowed.trim().toLowerCase(Locale.ROOT);
            if (pattern.equals("*")) return true;
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // ".example.com"
                if (host.endsWith(suffix) || host.equals(pattern.substring(2))) return true;
                continue;
            }
            if (host.equals(pattern)) return true;
        }
        return false;
    }

    /** The host of a URL, or {@code null} when it cannot be read. */
    public static String hostOf(String url) {
        if (Strings.isBlank(url)) return null;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host != null) return host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            // Fall through to a manual parse for strings the URI parser rejects.
        }
        String text = url.trim().toLowerCase(Locale.ROOT);
        int scheme = text.indexOf("://");
        if (scheme >= 0) text = text.substring(scheme + 3);
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        int colon = text.indexOf(':');
        if (colon >= 0) text = text.substring(0, colon);
        int at = text.indexOf('@');
        if (at >= 0) text = text.substring(at + 1);
        return text.isEmpty() ? null : text;
    }

    /** True when a relative path would leave the data folder. */
    public boolean escapesDataFolder(String relativePath) {
        if (dataFolder == null) return false;
        try {
            Path resolved = io.astra.util.FileUtil.resolveSafely(dataFolder, relativePath);
            return !resolved.startsWith(dataFolder);
        } catch (java.io.IOException e) {
            return true;
        }
    }

    /** Every trusted module name from {@code security.yml: modules.trusted-modules}. */
    public List<String> trustedModules() {
        if (config == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String entry : config.file("security.yml").getStringList("modules.trusted-modules", List.of())) {
            if (!Strings.isBlank(entry)) out.add(entry.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private void deny(String capability, String reason, ExecContext context) {
        String key = capability + ":" + reason;
        if (deniedOnce.add(key)) {
            logger.warn("Blocked a " + capability + " from " + describe(context) + ": " + reason);
        }
        throw new ActionFailure("Blocked by security.yml - " + reason);
    }

    private static String describe(ExecContext context) {
        if (context == null) return "a script";
        String script = context.script() == null ? "unknown script" : context.script().name();
        if (context.rule() != null && context.rule().span() != null) {
            return script + " (" + context.rule().span() + ")";
        }
        return script;
    }

    private static String quote(String text) {
        return text.length() > 120 ? text.substring(0, 117) + "..." : text;
    }
}
