package com.vouchq.notify;

/**
 * Minimal HTTP POST seam shared by the webhook-style channels (Webhook, Slack).
 * The production implementation uses the JDK {@link java.net.http.HttpClient};
 * unit tests substitute a capturing stub so no real network call is made.
 */
@FunctionalInterface
public interface HttpPoster {

    /**
     * POST {@code body} to {@code url} with the given {@code Content-Type}.
     *
     * @throws RuntimeException on transport failure (caller / service isolates it)
     */
    void post(String url, String contentType, String body);
}
