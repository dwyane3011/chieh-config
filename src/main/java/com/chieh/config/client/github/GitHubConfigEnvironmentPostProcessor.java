package com.chieh.config.client.github;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Early stage of GitHub config loading, following Apollo's approach.
 *
 * <p>Registered via {@code META-INF/spring.factories}. It always runs early —
 * right after {@link ConfigDataEnvironmentPostProcessor}, so {@code application.yml}
 * is loaded and the {@code chieh.config.github.*} switches can be read — but
 * <em>before</em> the logging system initialises.
 *
 * <p>Whether it actually loads here depends on the
 * {@code chieh.config.github.eagerLoad.enabled} switch (read from the
 * environment, so it may live in {@code application.yml}):
 * <ul>
 *   <li><b>eager = true</b>: load now, before Logback initialises, so
 *       {@code logback-spring.xml} can reference GitHub-sourced values via
 *       {@code <springProperty>}.</li>
 *   <li><b>eager = false</b> (default): do nothing here; the lazy fallback
 *       ({@link GitHubConfigApplicationContextInitializer}) loads later, after
 *       the logging system is up.</li>
 * </ul>
 *
 * <p>Unlike an ordering-based switch, reading {@code eager} from the environment
 * inside this method (rather than from {@code getOrder()}) is what lets the
 * switch live in {@code application.yml} — the same technique Apollo uses.
 */
public class GitHubConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * Always run right after {@link ConfigDataEnvironmentPostProcessor} so that
     * {@code application.yml} (and thus the {@code chieh.config.github.*}
     * switches) is available, while still finishing before the logging system
     * initialises.
     */
    private static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    private final Log log;
    private final GitHubConfigLoadingCoordinator coordinator;

    /**
     * Constructor used by Spring Boot. The {@link DeferredLogFactory} lets us log
     * during the bootstrap phase before the logging system is fully initialised.
     */
    public GitHubConfigEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(GitHubConfigEnvironmentPostProcessor.class);
        this.coordinator = new GitHubConfigLoadingCoordinator(this.log, new GitHubConfigLoader());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!GitHubConfigLoadingCoordinator.isEagerEnabled(environment)) {
            // Lazy mode: defer to GitHubConfigApplicationListener.
            return;
        }
        coordinator.contribute(environment, application.getAllSources(), "eager mode");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
