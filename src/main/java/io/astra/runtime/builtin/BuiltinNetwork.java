package io.astra.runtime.builtin;

import io.astra.data.DataStore;
import io.astra.runtime.ActionFailure;
import io.astra.runtime.ExecContext;
import io.astra.runtime.Value;
import io.astra.runtime.action.ActionDefinition;
import io.astra.runtime.action.ActionRegistry;
import io.astra.runtime.net.HttpService;

import org.bukkit.entity.Entity;

/**
 * Outbound network vocabulary: HTTP requests and webhooks.
 *
 * <p>Both actions follow the same contract, which is dictated by the server rules
 * rather than by convenience:</p>
 * <ul>
 *   <li>the security gate runs <em>synchronously</em>, on the calling thread, so a
 *       denied request stops the rule immediately with the gate's explanation;</li>
 *   <li>the request itself runs on an async thread through
 *       {@code SchedulerService.runAsync} - never on the tick thread, on any platform
 *       including Folia;</li>
 *   <li>the answer is written back into the script's data as
 *       {@code <key>.ok}, {@code <key>.status} and {@code <key>.body}, so the next
 *       rule (or a later run of the same rule) can act on it. The rule body is not
 *       suspended: an automation plugin must never stall a tick waiting for a socket.</li>
 * </ul>
 */
public final class BuiltinNetwork {

    private BuiltinNetwork() {
    }

    /** Register the HTTP and webhook actions. */
    public static void registerAll(ActionRegistry registry) {
        registry.register(ActionDefinition.builder("http-request",
                "Requests a URL in the background and stores the answer as data")
            .syntax("http get <url:text> as <key:data key>")
            .syntax("http get <url:text> into <key:data key>")
            .syntax("send http get <url:text> to <key:data key>")
            .syntax("http post <url:text> with <body:text...> as <key:data key>")
            .syntax("http request <url:text> as <key:data key>")
            .example("http get \"https://api.example.com/price\" as api")
            .security()
            .executes((context, arguments) -> {
                HttpService http = requireHttp(context);
                String url = arguments.string("url").trim();
                String key = arguments.string("key");
                String body = arguments.has("body") ? arguments.string("body") : null;
                if (url.isEmpty()) throw new ActionFailure("No URL given for the HTTP request");
                if (key.isEmpty()) throw new ActionFailure("No data key given for the HTTP answer");
                // Deny before anything is scheduled, so a forbidden target is reported
                // as a script error instead of a silent background failure.
                context.services().security().checkHttp(url, false, context);

                DataStore data = context.data();
                Entity holder = holder(context);
                context.services().scheduler().runAsync(() -> {
                    HttpService.Response response = body == null
                        ? http.get(url, context)
                        : http.post(url, body, "text/plain; charset=utf-8", false, context);
                    store(context, data, holder, key, response);
                });
            })
            .build());

        registry.register(ActionDefinition.builder("webhook",
                "Posts a message to a webhook URL in the background")
            .syntax("send webhook <url:text> with <message:text...>")
            .syntax("webhook <url:text> with <message:text...>")
            .syntax("post webhook <url:text> <message:text...>")
            .example("send webhook \"https://discord.com/api/webhooks/...\" with \"Player %player% joined\"")
            .security()
            .executes((context, arguments) -> {
                HttpService http = requireHttp(context);
                String url = arguments.string("url").trim();
                String message = arguments.has("message") ? arguments.string("message") : "";
                if (url.isEmpty()) throw new ActionFailure("No webhook URL given");
                context.services().security().checkHttp(url, true, context);

                String payload = HttpService.webhookPayload(
                    context.services().text().render(message,
                        text -> context.services().placeholders().resolve(text, context)));
                context.services().scheduler().runAsync(() -> {
                    HttpService.Response response = http.post(url, payload, "application/json", true, context);
                    if (response.failed()) {
                        context.services().logger().warn("Webhook delivery failed ("
                            + HttpService.redact(url) + "): " + response.error());
                    } else {
                        context.services().logger().debug("Webhook delivered to "
                            + HttpService.redact(url) + " (" + response.status() + ")");
                    }
                });
            })
            .build());
    }

    private static HttpService requireHttp(ExecContext context) {
        HttpService http = context.services().http();
        if (http == null) {
            throw new ActionFailure("HTTP support is not available in this build");
        }
        return http;
    }

    /** The entity the answer belongs to, or {@code null} for a global key. */
    private static Entity holder(ExecContext context) {
        Entity actor = context.actor();
        if (actor != null) return actor;
        return context.player();
    }

    /** Writes the response into the target's data (or globally when there is no target). */
    private static void store(ExecContext context, DataStore data, Entity holder, String key,
                              HttpService.Response response) {
        Value status = Value.num(response.status());
        Value body = Value.str(response.body());
        Value ok = Value.bool(response.ok());
        if (holder == null) {
            data.setGlobal(key + ".status", status);
            data.setGlobal(key + ".body", body);
            data.setGlobal(key + ".ok", ok);
        } else {
            data.set(holder, key + ".status", status);
            data.set(holder, key + ".body", body);
            data.set(holder, key + ".ok", ok);
        }
        if (response.failed()) {
            context.services().logger().debug("HTTP request for data key '" + key + "' failed: " + response.error());
        }
    }
}
