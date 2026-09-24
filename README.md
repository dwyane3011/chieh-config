# chieh-config

English | [中文版](./README.zh-CN.md)

A multi-module Spring Boot project built on **Java 21**. It provides an
Apollo-like, read-only config client that pulls a service's configuration from
one or more `.properties` files in a **GitHub** repository at startup.

## Modules

- `chieh-config-client` — the library. Provides `@EnableChiehConfig`. When
  placed on a service's main class, configuration is fetched from GitHub before
  the context refreshes and exposed as ordinary Spring properties. Packaged as a
  plain jar (not an executable app).
- `chieh-config-client-demo` — a runnable Spring Boot service that depends on
  the library and demonstrates/tests it. Runs on port 8080 and exposes endpoints
  to inspect what was loaded.

## Requirements

- JDK 21
- Maven 3.6.3+ (Spring Boot 3.3.x requires it)

## Using `@EnableChiehConfig`

Add the annotation to your application's main class:

```java
import com.chieh.config.client.annotation.EnableChiehConfig;

@EnableChiehConfig(
        repo  = "my-org/my-config-repo",
        paths = { "common.properties", "services/order-service.properties" },
        ref   = "main")
@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

At startup the client fetches each file from GitHub and injects its keys into
the Spring `Environment`, so they can be read via `@Value`,
`@ConfigurationProperties`, or `Environment.getProperty(...)` — just like local
`application.yml` values. Remote values take precedence over local
`application.yml` by default.

**Only `.properties` files are supported.** You may list multiple files; they
are loaded in order and **later files override earlier ones** (so put shared
defaults first and service-specific overrides last).

### Annotation attributes

| Attribute        | Default                       | Meaning                                                       |
|------------------|-------------------------------|---------------------------------------------------------------|
| `repo`           | *(empty)*                     | GitHub repo in `owner/name` form                              |
| `paths`          | *(empty)*                     | One or more `.properties` file paths inside the repo          |
| `ref`            | `main`                        | Branch, tag, or commit SHA                                    |
| `tokenProperty`  | `chieh.config.github.token`   | Property key that holds a GitHub token                        |
| `failFast`       | `true`                        | Fail startup if a file can't be loaded                        |

### Property overrides

Every attribute can be supplied or overridden through `chieh.config.github.*`
properties (handy for per-environment values). Properties win over annotation
attributes. `paths` is a comma-separated list:

```yaml
chieh:
  config:
    github:
      repo: my-org/my-config-repo
      paths: common.properties,services/order-service.properties
      ref: main
      fail-fast: true
```

### Private repos and rate limits

Supply a GitHub personal access token via the property named by
`tokenProperty` (default `chieh.config.github.token`). Prefer an environment
variable so the token is never committed:

```bash
export CHIEH_CONFIG_GITHUB_TOKEN=ghp_xxx
```

Spring maps `CHIEH_CONFIG_GITHUB_TOKEN` to `chieh.config.github.token`
automatically via relaxed binding.

## Build

```bash
mvn clean package
```

## Run the demo

```bash
mvn -pl chieh-config-client-demo spring-boot:run
```

The demo module (`chieh-config-client-demo`) uses `@EnableChiehConfig(failFast = false)`
with a blank repo/paths, so it starts even without a real repo. Point it at a
repository via `chieh.config.github.*` and inspect what was loaded:

- `GET http://localhost:8080/config?key=<some.key>` — resolve one property
- `GET http://localhost:8080/config/github` — list keys loaded from GitHub

## How it works

`@EnableChiehConfig` is detected by a Spring Boot `EnvironmentPostProcessor`
(registered in `META-INF/spring.factories`). The processor resolves the repo
coordinates, calls the GitHub contents API for each raw `.properties` file,
parses it with Spring Boot's properties loader, and adds the results as
high-precedence `PropertySource`s (later files ranked above earlier ones) —
early enough that all beans see the values.

### Loading modes: eager vs lazy

Loading works in two stages (the same pattern Apollo uses), selected by the
`chieh.config.github.eager` switch (default **`false`**):

| Mode  | `eager` | When it loads                                          | Logback can see GitHub values? |
|-------|---------|--------------------------------------------------------|--------------------------------|
| Lazy  | `false` (default) | On `ApplicationPreparedEvent`, **after** the logging system initialises | No           |
| Eager | `true`  | In an `EnvironmentPostProcessor`, right after `application.yml` loads, **before Logback** | Yes |

- **Eager stage** — `GitHubConfigEnvironmentPostProcessor` always runs early
  (right after `ConfigDataEnvironmentPostProcessor`, so `application.yml` is
  available), but it only *loads* when `eager = true`. Loading here happens
  before Logback initialises, so `logback-spring.xml` can reference
  GitHub-sourced values via `<springProperty>`.
- **Lazy stage** — `GitHubConfigApplicationListener` listens for
  `ApplicationPreparedEvent` (after the logging system is up) and loads when the
  eager stage didn't. A marker property ensures the config is never loaded twice.

Either way the config is in the `Environment` before beans are created, so
`@Value` / `@ConfigurationProperties` always see it. Only Logback's own config
differs between the modes.

#### Setting the switch

Because the switch is read from the environment *inside* a post-processor method
(not from `getOrder()`), it can live in `application.yml`:

```yaml
chieh:
  config:
    github:
      eager: true
```

A system property or environment variable overrides the yml value (handy for
one-off runs):

```bash
-Dchieh.config.github.eager=true          # system property
export CHIEH_CONFIG_GITHUB_EAGER=true     # bash
$env:CHIEH_CONFIG_GITHUB_EAGER = "true"   # PowerShell
```

The startup log shows which stage ran, e.g.
`Loaded chieh-config from GitHub [eager mode]: ...` or `[lazy mode]`.

## Tech stack

- Spring Boot 3.3.x
- Spring Web
- Java 21
