# chieh-config-client

English | [中文版](./README.zh-CN.md)

> A lightweight, Apollo-inspired configuration client for Spring Boot that loads a
> service's configuration from `.properties` files in a **GitHub repository** at startup.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](../LICENSE)

Add one annotation, point it at a GitHub repo, and your `.properties` files become
ordinary Spring properties — readable through `@Value`, `@ConfigurationProperties`,
or `Environment`, exactly like a local `application.yml`.

---

## Features

- **One-annotation setup** — just add `@EnableChiehConfig` to your main class.
- **GitHub as the config source** — reads `.properties` files straight from any repo via the GitHub Contents API.
- **Public & private repos** — supply a personal access token for private repos and higher rate limits.
- **Multi-file with clear override order** — list several files; later files win over earlier ones.
- **Eager or lazy loading** — load before Logback initialises (so `logback-spring.xml` sees remote values), or lazily after the logging system is up.
- **Remote-wins precedence** — GitHub values override local `application.yml` by default.
- **Minimal dependencies** — only the JDK HTTP client plus Spring Boot itself.
- **Fail-fast or best-effort** — choose whether a fetch failure aborts startup.

---

## Requirements

- JDK 21
- Spring Boot 3.3.x
- Maven 3.6.3+

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.chieh</groupId>
    <artifactId>chieh-config-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Annotate your main class

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

### 3. Read the config anywhere

```java
@Value("${log.application.maxHistory}")
private String maxHistory;
```

At startup the client fetches each file from GitHub and injects its keys into the
Spring `Environment` **before beans are created**, so `@Value`,
`@ConfigurationProperties`, and `Environment.getProperty(...)` all see the remote
values.

> **Only `.properties` files are supported.** List multiple files if you like —
> they load in order and **later files override earlier ones**, so put shared
> defaults first and service-specific overrides last.

---

## Configuration

### Annotation attributes

| Attribute       | Default                     | Meaning                                              |
|-----------------|-----------------------------|------------------------------------------------------|
| `repo`          | *(empty)*                   | GitHub repo in `owner/name` form                     |
| `paths`         | *(empty)*                   | One or more `.properties` paths inside the repo      |
| `ref`           | `main`                      | Branch, tag, or commit SHA                           |
| `tokenProperty` | `chieh.config.github.token` | Name of the property that holds a GitHub token       |
| `failFast`      | `true`                      | Abort startup if a file can't be fetched/parsed      |

### Overriding via properties

Every attribute can be supplied or overridden under the `chieh.config.github.*`
prefix — handy for per-environment values. **Properties win over annotation
attributes.** `paths` is a comma-separated list.

```yaml
chieh:
  config:
    github:
      repo: my-org/my-config-repo
      paths: common.properties,services/order-service.properties
      ref: main
      fail-fast: true
      eager: false
```

---

## Private repositories & tokens

For a private repo (or to avoid GitHub's anonymous rate limits), supply a personal
access token via the property named by `tokenProperty` (default
`chieh.config.github.token`).

**Never hard-code the token.** Provide it through an environment variable so it is
never committed:

```bash
# bash
export CHIEH_CONFIG_GITHUB_TOKEN=github_pat_xxx
```

```powershell
# PowerShell
$env:CHIEH_CONFIG_GITHUB_TOKEN = "github_pat_xxx"
```

Spring's relaxed binding maps `CHIEH_CONFIG_GITHUB_TOKEN` → `chieh.config.github.token`
automatically. The token needs only **Contents: Read-only** on the target repository.

---

## Eager vs lazy loading

Loading happens in two stages (the same pattern Apollo uses), selected by
`chieh.config.github.eager` (default **`false`**):

| Mode  | `eager`           | When it loads                                                                       | `logback-spring.xml` sees remote values? |
|-------|-------------------|-------------------------------------------------------------------------------------|-------------------------------------------|
| Lazy  | `false` (default) | On `ApplicationPreparedEvent`, **after** the logging system initialises             | No  |
| Eager | `true`            | In an `EnvironmentPostProcessor`, right after `application.yml`, **before Logback** | Yes |

- **Eager** — `GitHubConfigEnvironmentPostProcessor` runs early (right after
  `ConfigDataEnvironmentPostProcessor`, so `application.yml` is available) and loads
  before Logback initialises. Use this when `logback-spring.xml` must reference
  GitHub-sourced values via `<springProperty>`.
- **Lazy** — `GitHubConfigApplicationListener` handles `ApplicationPreparedEvent`
  (after logging is up) and loads only if eager didn't. A marker property guarantees
  config is never loaded twice.

Either way the config lands in the `Environment` before beans are created, so
`@Value` / `@ConfigurationProperties` always see it — only Logback's own
configuration differs between the modes.

Set the switch in `application.yml`, or override per run:

```bash
-Dchieh.config.github.eager=true          # JVM system property
export CHIEH_CONFIG_GITHUB_EAGER=true     # bash
$env:CHIEH_CONFIG_GITHUB_EAGER = "true"   # PowerShell
```

The startup log shows which stage ran, e.g.
`Loaded chieh-config from GitHub [eager mode]: ...` or `[lazy mode]`.

---

## Precedence

Where remote config sits in Spring's property-source order:

```
command-line args  >  -D system props  >  env vars
      >  GitHub config (this client)  >  application.yml  >  defaults
```

So GitHub values override local `application.yml`, but command-line arguments, JVM
system properties, and environment variables still win over GitHub. Within the
remote set, later files in `paths` override earlier ones.

---

## How it works

`@EnableChiehConfig` is picked up by a Spring Boot `EnvironmentPostProcessor` /
`ApplicationListener` registered in `META-INF/spring.factories`. The flow:

1. Resolve settings by merging `@EnableChiehConfig` attributes with
   `chieh.config.github.*` properties (properties win).
2. For each path, call the GitHub Contents API with the raw media type
   (`GET /repos/{owner}/{repo}/contents/{path}?ref={ref}`), sending the token as a
   `Bearer` header when present.
3. Parse each file with Spring Boot's `PropertiesPropertySourceLoader`.
4. Add the results as high-precedence `PropertySource`s (later files ranked above
   earlier ones), early enough that all beans see them.

### Key types

| Class                                    | Role                                                    |
|------------------------------------------|---------------------------------------------------------|
| `EnableChiehConfig`                      | The annotation that turns the client on                 |
| `GitHubConfigEnvironmentPostProcessor`   | Eager stage — loads before Logback                      |
| `GitHubConfigApplicationListener`        | Lazy stage — loads after logging is up                  |
| `GitHubConfigLoadingCoordinator`         | Coordinates both stages: shared load-and-inject logic + double-load guard |
| `GitHubConfigLoader`                     | Fetches raw files over the GitHub API and parses them   |
| `GitHubConfigProperties`                 | Resolved, immutable settings record                     |
| `GitHubConfigException`                  | Raised on fetch/parse failures                          |

---

## Build

```bash
mvn clean package
```

Packaged as a plain library jar (not an executable app).

---

## License

Released under the MIT License. See [LICENSE](../LICENSE) for details.
