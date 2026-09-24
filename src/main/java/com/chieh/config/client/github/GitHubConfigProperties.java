package com.chieh.config.client.github;

import java.util.List;

/**
 * Resolved settings describing which GitHub files to load configuration from.
 *
 * <p>Instances are built by combining {@code @EnableChiehConfig} attributes with
 * matching {@code chieh.config.github.*} properties, where properties win.
 * Only {@code .properties} files are supported; {@link #paths()} may list
 * several, loaded in order with later files overriding earlier ones.
 */
public record GitHubConfigProperties(
        String repo,
        List<String> paths,
        String ref,
        String token,
        boolean failFast) {

    /** Property prefix used to override annotation attributes. */
    public static final String PREFIX = "chieh.config.github";

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public boolean isConfigured() {
        return repo != null && !repo.isBlank()
                && paths != null && !paths.isEmpty();
    }
}
