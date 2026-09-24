# chieh-config-client

[English](./README.md) | 中文版

> 一个轻量、受 Apollo 启发的 Spring Boot 配置客户端，在应用启动时从 **GitHub 仓库**中的
> `.properties` 文件加载服务配置。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](../LICENSE)

只需加一个注解，指向一个 GitHub 仓库，你的 `.properties` 文件就会变成普通的 Spring 属性 ——
可以通过 `@Value`、`@ConfigurationProperties` 或 `Environment` 读取，和本地 `application.yml`
完全一样。

---

## 特性

- **一个注解搞定** —— 只需在启动类上加 `@EnableChiehConfig`。
- **以 GitHub 作为配置源** —— 通过 GitHub Contents API 直接读取任意仓库中的 `.properties` 文件。
- **支持公开与私有仓库** —— 为私有仓库（以及更高的限流额度）提供个人访问令牌。
- **多文件、覆盖顺序清晰** —— 可列出多个文件，靠后的文件覆盖靠前的。
- **饥饿或懒加载** —— 可在 Logback 初始化之前加载（让 `logback-spring.xml` 能读到远程值），
  也可在日志系统就绪之后再懒加载。
- **远程优先** —— GitHub 的值默认覆盖本地 `application.yml`。
- **依赖极少** —— 仅用 JDK 自带的 HTTP 客户端加 Spring Boot 本身。
- **快速失败或尽力而为** —— 可选择拉取失败时是否中断启动。

---

## 环境要求

- JDK 21
- Spring Boot 3.3.x
- Maven 3.6.3+

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chieh</groupId>
    <artifactId>chieh-config-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. 在启动类上加注解

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

### 3. 在任意位置读取配置

```java
@Value("${log.application.maxHistory}")
private String maxHistory;
```

启动时，客户端会从 GitHub 拉取每个文件，并在 **bean 创建之前**把其中的键注入 Spring 的
`Environment`，因此 `@Value`、`@ConfigurationProperties` 和 `Environment.getProperty(...)`
都能读到这些远程值。

> **仅支持 `.properties` 文件。** 你可以列出多个文件 —— 它们按顺序加载，**靠后的文件覆盖靠前的**，
> 所以把共享的默认值放前面，服务级的覆盖值放后面。

---

## 配置

### 注解属性

| 属性            | 默认值                       | 含义                                       |
|-----------------|------------------------------|--------------------------------------------|
| `repo`          | *(空)*                       | GitHub 仓库，`owner/name` 格式              |
| `paths`         | *(空)*                       | 仓库内一个或多个 `.properties` 文件路径     |
| `ref`           | `main`                       | 分支、tag 或 commit SHA                     |
| `tokenProperty` | `chieh.config.github.token`  | 存放 GitHub token 的属性名                  |
| `failFast`      | `true`                       | 文件拉取/解析失败时是否中断启动             |

### 通过属性覆盖

每个属性都可以在 `chieh.config.github.*` 前缀下提供或覆盖 —— 适合按环境区分的值。
**属性的优先级高于注解属性。** `paths` 是一个逗号分隔的列表。

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

## 私有仓库与令牌

对于私有仓库（或为了避免 GitHub 的匿名限流），需要通过 `tokenProperty` 指定的属性名
（默认 `chieh.config.github.token`）提供个人访问令牌。

**切勿把令牌硬编码。** 用环境变量提供，确保它不会被提交进仓库：

```bash
# bash
export CHIEH_CONFIG_GITHUB_TOKEN=github_pat_xxx
```

```powershell
# PowerShell
$env:CHIEH_CONFIG_GITHUB_TOKEN = "github_pat_xxx"
```

Spring 的宽松绑定会自动把 `CHIEH_CONFIG_GITHUB_TOKEN` 映射到 `chieh.config.github.token`。
令牌只需对目标仓库拥有 **Contents: Read-only（内容：只读）** 权限即可。

---

## 饥饿加载 vs 懒加载

加载分两个阶段完成（和 Apollo 采用的模式相同），由 `chieh.config.github.eager` 选择
（默认 **`false`**）：

| 模式  | `eager`         | 加载时机                                                                | `logback-spring.xml` 能读到远程值？ |
|-------|-----------------|-------------------------------------------------------------------------|-------------------------------------|
| 懒加载 | `false`（默认） | 在 `ApplicationPreparedEvent` 时，**晚于**日志系统初始化                  | 否 |
| 饥饿   | `true`          | 在 `EnvironmentPostProcessor` 中，`application.yml` 之后、**Logback 之前** | 是 |

- **饥饿** —— `GitHubConfigEnvironmentPostProcessor` 很早运行（紧跟
  `ConfigDataEnvironmentPostProcessor` 之后，此时 `application.yml` 已可用），并在 Logback
  初始化之前加载。当 `logback-spring.xml` 需要通过 `<springProperty>` 引用来自 GitHub 的值时，
  使用此模式。
- **懒加载** —— `GitHubConfigApplicationListener` 处理 `ApplicationPreparedEvent`
  （在日志系统就绪之后），仅当饥饿阶段未加载时才加载。一个标记属性确保配置不会被加载两次。

无论哪种模式，配置都在 bean 创建之前进入 `Environment`，因此 `@Value` /
`@ConfigurationProperties` 始终能读到 —— 两种模式的唯一区别在于 Logback 自身的配置能否读到。

在 `application.yml` 中设置该开关，或按次运行覆盖：

```bash
-Dchieh.config.github.eager=true          # JVM 系统属性
export CHIEH_CONFIG_GITHUB_EAGER=true     # bash
$env:CHIEH_CONFIG_GITHUB_EAGER = "true"   # PowerShell
```

启动日志会显示实际运行的是哪个阶段，例如
`Loaded chieh-config from GitHub [eager mode]: ...` 或 `[lazy mode]`。

---

## 优先级

远程配置在 Spring 属性源顺序中的位置：

```
命令行参数  >  -D 系统属性  >  环境变量
      >  GitHub 配置（本客户端）  >  application.yml  >  默认值
```

因此 GitHub 的值会覆盖本地 `application.yml`，但命令行参数、JVM 系统属性和环境变量仍然优先于
GitHub。在远程配置内部，`paths` 中靠后的文件覆盖靠前的。

---

## 工作原理

`@EnableChiehConfig` 由在 `META-INF/spring.factories` 中注册的 Spring Boot
`EnvironmentPostProcessor` / `ApplicationListener` 检测。流程如下：

1. 合并 `@EnableChiehConfig` 注解属性与 `chieh.config.github.*` 属性来解析设置（属性优先）。
2. 对每个路径，用 raw 媒体类型调用 GitHub Contents API
   （`GET /repos/{owner}/{repo}/contents/{path}?ref={ref}`），存在令牌时以 `Bearer` 请求头发送。
3. 用 Spring Boot 的 `PropertiesPropertySourceLoader` 解析每个文件。
4. 把结果作为高优先级的 `PropertySource` 加入（靠后的文件排在更高优先级），时机足够早，
   所有 bean 都能看到。

### 核心类型

| 类                                        | 职责                                                     |
|-------------------------------------------|----------------------------------------------------------|
| `EnableChiehConfig`                       | 开启客户端的注解                                          |
| `GitHubConfigEnvironmentPostProcessor`    | 饥饿阶段 —— 在 Logback 之前加载                           |
| `GitHubConfigApplicationListener`         | 懒加载阶段 —— 在日志系统就绪之后加载                      |
| `GitHubConfigLoadingCoordinator`          | 协调两个阶段：共享的加载与注入逻辑 + 防重复加载保护       |
| `GitHubConfigLoader`                      | 通过 GitHub API 拉取原始文件并解析                        |
| `GitHubConfigProperties`                  | 解析后的、不可变的设置记录                                |
| `GitHubConfigException`                   | 拉取/解析失败时抛出                                       |

---

## 构建

```bash
mvn clean package
```

打包为普通的库 jar（不是可执行应用）。

---

## 许可证

基于 MIT 许可证发布。详见 [LICENSE](../LICENSE)。
