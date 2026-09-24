package com.chieh.config.client.github;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Fetches one or more {@code .properties} configuration files from GitHub and
 * turns them into Spring {@link PropertySource}s.
 *
 * <p>Uses the GitHub REST "contents" API with the raw media type, so it works
 * for both public and private repositories (a token is required for the
 * latter). Only the JDK HTTP client is used, keeping the client dependency-free
 * beyond Spring Boot itself.
 *
 * <p>Files are loaded in the order listed in {@link GitHubConfigProperties#paths()}.
 * The returned list preserves that order; callers give later files higher
 * precedence so they override earlier ones.
 */
public class GitHubConfigLoader {

    private static final String API_BASE = "https://api.github.com/repos/";
    private static final String RAW_MEDIA_TYPE = "application/vnd.github.raw+json";

    private final HttpClient httpClient;
    private final PropertySourceLoader propertiesLoader = new PropertiesPropertySourceLoader();

    public GitHubConfigLoader() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    GitHubConfigLoader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Loads every configured file from GitHub and parses each into a property
     * source, in declared order.
     *
     * @throws GitHubConfigException if any file cannot be fetched or parsed
     */
    public List<PropertySource<?>> load(GitHubConfigProperties properties) {
        List<PropertySource<?>> result = new ArrayList<>();
        for (String path : properties.paths()) {
            String content = fetchRaw(properties, path);
            result.addAll(parse(properties, path, content));
        }
        return result;
    }

    private String fetchRaw(GitHubConfigProperties properties, String path) {
        URI uri = buildUri(properties, path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", RAW_MEDIA_TYPE)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
        if (properties.hasToken()) {
            request.header("Authorization", "Bearer " + properties.token());
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }
            throw new GitHubConfigException(
                    "GitHub returned HTTP %d for %s (repo=%s, path=%s, ref=%s)"
                            .formatted(status, uri, properties.repo(), path, properties.ref()));
        } catch (IOException e) {
            throw new GitHubConfigException("Failed to fetch config from " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubConfigException("Interrupted while fetching config from " + uri, e);
        }
    }

    private URI buildUri(GitHubConfigProperties properties, String path) {
        // GET /repos/{owner}/{repo}/contents/{path}?ref={ref}
        String encodedPath = encodePath(path);
        String ref = URLEncoder.encode(properties.ref(), StandardCharsets.UTF_8);
        return URI.create(API_BASE + properties.repo() + "/contents/" + encodedPath + "?ref=" + ref);
    }

    private String encodePath(String path) {
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private List<PropertySource<?>> parse(GitHubConfigProperties properties, String path, String content) {
        String name = "chieh-github:" + properties.repo() + "/" + path + "@" + properties.ref();
        Resource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), name);
        try {
            List<PropertySource<?>> sources = propertiesLoader.load(name, resource);
            if (sources.isEmpty()) {
                throw new GitHubConfigException("Config file was empty: " + path);
            }
            return sources;
        } catch (IOException e) {
            throw new GitHubConfigException("Failed to parse config file: " + path, e);
        }
    }
}
