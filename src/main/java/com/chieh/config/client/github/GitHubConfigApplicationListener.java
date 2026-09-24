package com.chieh.config.client.github;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Lazy stage of GitHub config loading (the default).
 *
 * <p>Registered via {@code META-INF/spring.factories}. It listens for
 * {@link ApplicationPreparedEvent}, which fires after the context is prepared
 * and <em>after</em> the logging system has initialised. When eager mode is off
 * (the default), {@link GitHubConfigEnvironmentPostProcessor} does nothing and
 * this listener performs the load instead.
 *
 * <p>If eager mode already loaded the config, {@link GitHubConfigLoadingCoordinator}'s
 * marker makes this a no-op, so the two stages never load twice.
 */
public class GitHubConfigApplicationListener
        implements ApplicationListener<ApplicationPreparedEvent>, Ordered {

    private final Log log = LogFactory.getLog(GitHubConfigApplicationListener.class);

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        ConfigurableEnvironment environment = event.getApplicationContext().getEnvironment();

        // In eager mode the post-processor already loaded (and set the marker);
        // the contributor will short-circuit. We still call through so the marker
        // check is centralised in one place.
        GitHubConfigLoadingCoordinator coordinator =
                new GitHubConfigLoadingCoordinator(log, new GitHubConfigLoader());
        coordinator.contribute(environment, event.getSpringApplication().getAllSources(), "lazy mode");
    }

    @Override
    public int getOrder() {
        // Run late so the logging system is definitely up before we log.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
