package com.chieh.config.client.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the chieh-config client in a Spring Boot service.
 *
 * <p>When present on the application's main class, configuration is fetched
 * from a GitHub repository at startup (before the application context refreshes)
 * and exposed as regular Spring properties, so it can be consumed via
 * {@code @Value}, {@code @ConfigurationProperties}, or {@code Environment}.
 *
 * <p>Only {@code .properties} files are supported. Multiple files may be
 * listed; they are loaded in order and later files override earlier ones.
 *
 * <pre>{@code
 * @EnableChiehConfig(
 *     repo  = "my-org/my-config-repo",
 *     paths = { "common.properties", "services/order-service.properties" },
 *     ref   = "main")
 * @SpringBootApplication
 * public class OrderServiceApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(OrderServiceApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <p>Every attribute can also be supplied (or overridden) through Spring
 * properties under the {@code chieh.config.github.*} prefix, which is handy for
 * environment-specific values such as the access token. Annotation attributes
 * act as defaults; matching properties in the environment take precedence.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface EnableChiehConfig {

    /**
     * GitHub repository in {@code owner/name} form, e.g. {@code my-org/config}.
     * May be overridden by property {@code chieh.config.github.repo}.
     */
    String repo() default "";

    /**
     * Paths to the {@code .properties} config files within the repository,
     * e.g. {@code {"common.properties", "services/order-service.properties"}}.
     * Files are loaded in the order listed and later files override earlier
     * ones. Only {@code .properties} files are supported.
     * May be overridden by property {@code chieh.config.github.paths} (a
     * comma-separated list).
     */
    String[] paths() default {};

    /**
     * Git ref (branch, tag, or commit SHA) to read from. Defaults to
     * {@code main}. May be overridden by property {@code chieh.config.github.ref}.
     */
    String ref() default "main";

    /**
     * Name of the environment property that holds a GitHub personal access
     * token, used for private repositories and higher rate limits. Defaults to
     * {@code chieh.config.github.token}. The token itself should be supplied via
     * an environment variable or JVM property, never hard-coded.
     */
    String tokenProperty() default "chieh.config.github.token";

    /**
     * Whether startup should fail if a config file cannot be fetched or
     * parsed. When {@code false}, loading errors are logged and startup
     * continues without the remote config. Defaults to {@code true}.
     * May be overridden by property {@code chieh.config.github.fail-fast}.
     */
    boolean failFast() default true;
}
