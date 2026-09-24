package com.chieh.config.client.github;

import java.util.ArrayList;
import java.util.List;

import com.chieh.config.client.annotation.EnableChiehConfig;

import org.apache.commons.logging.Log;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Shared logic for contributing GitHub-backed configuration to a Spring
 * {@link ConfigurableEnvironment}.
 *
 * <p>Used by both loading stages so they behave identically:
 * <ul>
 *   <li>{@link GitHubConfigEnvironmentPostProcessor} — the early stage; when
 *       eager mode is on, it loads here, before Logback initialises.</li>
 *   <li>{@link GitHubConfigApplicationListener} — the late stage; the
 *       lazy-mode fallback that loads after the logging system is up.</li>
 * </ul>
 *
 * <p>A marker property is written to the environment after a successful (or
 * deliberately skipped) contribution so the two stages never load twice. This
 * mirrors Apollo's approach of reading its {@code apollo.bootstrap.eagerLoad.enabled}
 * switch from the environment and choosing when to inject.
 */
public final class GitHubConfigLoadingCoordinator {

    /**
     * Switch selecting eager (pre-Logback) loading. Unlike ordering-based
     * approaches, this is read from the {@link ConfigurableEnvironment} inside a
     * post-processor method, so it may live in {@code application.yml}. A system
     * property or {@code CHIEH_CONFIG_GITHUB_EAGERLOAD_ENABLED} env var still
     * overrides it. Named after Apollo's {@code apollo.bootstrap.eagerLoad.enabled}.
     */
    public static final String EAGER_PROPERTY = GitHubConfigProperties.PREFIX + ".eagerLoad.enabled";

    /** Environment variable that overrides {@link #EAGER_PROPERTY}. */
    private static final String EAGER_ENV = "CHIEH_CONFIG_GITHUB_EAGERLOAD_ENABLED";

    /** Marker set once a stage has handled loading, to prevent double loading. */
    private static final String LOADED_MARKER = GitHubConfigProperties.PREFIX + ".loaded";

    private final Log log;
    private final GitHubConfigLoader loader;

    public GitHubConfigLoadingCoordinator(Log log, GitHubConfigLoader loader) {
        this.log = log;
        this.loader = loader;
    }

    /**
     * Loads and injects GitHub config into the environment, unless another stage
     * already did. No-ops when {@code @EnableChiehConfig} is absent or when
     * repo/paths are not configured (respecting fail-fast).
     *
     * @param sources classes to scan for {@link EnableChiehConfig} (e.g. the
     *                application's primary sources)
     */
    public void contribute(ConfigurableEnvironment environment, Iterable<Object> sources, String stage) {
        if (isAlreadyLoaded(environment)) {
            return;
        }

        EnableChiehConfig annotation = findAnnotation(sources);
        if (annotation == null) {
            return;
        }

        GitHubConfigProperties properties = resolveProperties(annotation, environment);
        if (!properties.isConfigured()) {
            String message = "@EnableChiehConfig is present but no repo/paths were provided "
                    + "(set them on the annotation or via " + GitHubConfigProperties.PREFIX + ".repo/.paths).";
            if (properties.failFast()) {
                throw new GitHubConfigException(message);
            }
            log.warn(message);
            markLoaded(environment);
            return;
        }

        try {
            List<PropertySource<?>> loaded = loader.load(properties);
            injectHighestPrecedence(environment.getPropertySources(), loaded);
            log.info("Loaded chieh-config from GitHub [" + stage + "]: "
                    + properties.repo() + " " + properties.paths() + " @" + properties.ref());
        } catch (RuntimeException e) {
            if (properties.failFast()) {
                throw e;
            }
            log.warn("Failed to load chieh-config from GitHub; continuing without it: " + e.getMessage());
        } finally {
            markLoaded(environment);
        }
    }

    /**
     * Resolves whether eager mode is on. Precedence: system property
     * ({@link #EAGER_PROPERTY}), then the {@code CHIEH_CONFIG_GITHUB_EAGERLOAD_ENABLED}
     * env var, then the environment (which includes {@code application.yml}).
     * Defaults to {@code false}.
     */
    public static boolean isEagerEnabled(ConfigurableEnvironment environment) {
        String override = System.getProperty(EAGER_PROPERTY);
        if (override == null) {
            override = System.getenv(EAGER_ENV);
        }
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return environment.getProperty(EAGER_PROPERTY, Boolean.class, false);
    }

    private boolean isAlreadyLoaded(ConfigurableEnvironment environment) {
        return environment.getPropertySources().contains(LOADED_MARKER);
    }

    private void markLoaded(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();
        if (!sources.contains(LOADED_MARKER)) {
            sources.addLast(new org.springframework.core.env.MapPropertySource(
                    LOADED_MARKER, java.util.Map.of(LOADED_MARKER, Boolean.TRUE)));
        }
    }

    private EnableChiehConfig findAnnotation(Iterable<Object> sources) {
        for (Object source : sources) {
            Class<?> clazz = null;
            if (source instanceof Class<?> c) {
                clazz = c;
            } else if (source != null) {
                clazz = source.getClass();
            }
            if (clazz != null) {
                EnableChiehConfig annotation = AnnotationUtils.findAnnotation(clazz, EnableChiehConfig.class);
                if (annotation != null) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private GitHubConfigProperties resolveProperties(EnableChiehConfig annotation, ConfigurableEnvironment env) {
        String prefix = GitHubConfigProperties.PREFIX;
        String repo = env.getProperty(prefix + ".repo", annotation.repo());
        String ref = env.getProperty(prefix + ".ref", annotation.ref());
        boolean failFast = env.getProperty(prefix + ".fail-fast", Boolean.class, annotation.failFast());

        List<String> paths = resolvePaths(annotation, env, prefix);

        String tokenProperty = annotation.tokenProperty();
        String token = env.getProperty(tokenProperty);

        return new GitHubConfigProperties(
                trimToNull(repo),
                paths,
                trimToNull(ref),
                token,
                failFast);
    }

    private List<String> resolvePaths(EnableChiehConfig annotation, ConfigurableEnvironment env, String prefix) {
        String[] override = env.getProperty(prefix + ".paths", String[].class);
        String[] raw = (override != null) ? override : annotation.paths();

        List<String> paths = new ArrayList<>();
        for (String p : raw) {
            String trimmed = trimToNull(p);
            if (trimmed != null) {
                paths.add(trimmed);
            }
        }
        return paths;
    }

    private void injectHighestPrecedence(MutablePropertySources propertySources, List<PropertySource<?>> sources) {
        // Add remote sources near the front so remote config wins over
        // application.yml. Within the remote set, later files override earlier
        // ones: iterating forward with addFirst leaves the last file at the front.
        for (PropertySource<?> source : sources) {
            if (propertySources.contains(source.getName())) {
                propertySources.replace(source.getName(), source);
            } else {
                propertySources.addFirst(source);
            }
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
