# chieh-config

一个基于 **Java 21** 的多模块 Spring Boot 项目。它提供一个类 Apollo 的只读配置客户端，
在应用启动时从 **GitHub** 仓库中的一个或多个 `.properties` 文件拉取服务配置。

> 中文版 | [English](./README.md)

## 模块

- `chieh-config-client` —— 核心库。提供 `@EnableChiehConfig` 注解。将它标注在服务的启动类上后，
  配置会在应用上下文刷新之前从 GitHub 拉取，并作为普通的 Spring 属性暴露出来。打包为普通 jar
  （不是可执行应用）。
- `chieh-config-client-demo` —— 一个可运行的 Spring Boot 服务，依赖上面的库，用于演示和测试。
  运行在 8080 端口，并暴露了几个接口用于查看加载到的配置。

## 环境要求

- JDK 21
- Maven 3.6.3+（Spring Boot 3.3.x 要求）

## 使用 `@EnableChiehConfig`

把注解加到应用的启动类上：

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

启动时，客户端会从 GitHub 拉取每个文件，并把其中的键注入 Spring 的 `Environment`，因此可以通过
`@Value`、`@ConfigurationProperties` 或 `Environment.getProperty(...)` 读取 —— 和本地
`application.yml` 里的值用法完全一样。默认情况下，远程配置的优先级高于本地 `application.yml`。

**仅支持 `.properties` 文件。** 你可以列出多个文件；它们按顺序加载，**靠后的文件覆盖靠前的**
（所以把共享的默认值放前面，服务级的覆盖值放后面）。

### 注解属性

| 属性             | 默认值                        | 含义                                            |
|------------------|-------------------------------|-------------------------------------------------|
| `repo`           | *(空)*                        | GitHub 仓库，`owner/name` 格式                   |
| `paths`          | *(空)*                        | 仓库内一个或多个 `.properties` 文件路径          |
| `ref`            | `main`                        | 分支、tag 或 commit SHA                          |
| `tokenProperty`  | `chieh.config.github.token`   | 存放 GitHub token 的属性名                       |
| `failFast`       | `true`                        | 文件加载失败时是否让启动失败                     |

### 通过属性覆盖

每个属性都可以通过 `chieh.config.github.*` 属性提供或覆盖（适合按环境区分的值）。属性的优先级
高于注解属性。`paths` 是一个逗号分隔的列表：

```yaml
chieh:
  config:
    github:
      repo: my-org/my-config-repo
      paths: common.properties,services/order-service.properties
      ref: main
      fail-fast: true
```

### 私有仓库与限流

通过 `tokenProperty` 指定的属性名（默认 `chieh.config.github.token`）提供 GitHub 个人访问令牌。
建议用环境变量，避免 token 被提交进仓库：

```bash
export CHIEH_CONFIG_GITHUB_TOKEN=ghp_xxx
```

Spring 会通过宽松绑定（relaxed binding）自动把 `CHIEH_CONFIG_GITHUB_TOKEN` 映射到
`chieh.config.github.token`。

## 构建

```bash
mvn clean package
```

## 运行 demo

```bash
mvn -pl chieh-config-client-demo spring-boot:run
```

demo 模块（`chieh-config-client-demo`）使用 `@EnableChiehConfig(failFast = false)`，
且 repo/paths 留空，因此即使没有真实仓库也能正常启动。通过 `chieh.config.github.*` 指向一个
仓库后，可用以下接口查看加载结果：

- `GET http://localhost:8080/config?key=<某个键>` —— 解析单个属性
- `GET http://localhost:8080/config/github` —— 列出从 GitHub 加载到的所有键

## 工作原理

`@EnableChiehConfig` 由一个 Spring Boot `EnvironmentPostProcessor`（在 `META-INF/spring.factories`
中注册）检测到。该处理器解析仓库坐标，针对每个 `.properties` 文件调用 GitHub Contents API 获取
原始内容，用 Spring Boot 的 properties 解析器解析，然后把结果作为高优先级的 `PropertySource`
加入环境（靠后的文件排在更高优先级）—— 时机足够早，所有 bean 都能看到这些值。

### 加载模式：饥饿（eager）与懒加载（lazy）

加载分两个阶段完成（和 Apollo 采用的模式相同），由 `chieh.config.github.eager` 开关选择
（默认 **`false`**）：

| 模式  | `eager` | 加载时机                                                    | Logback 能否读到 GitHub 的值？ |
|-------|---------|-------------------------------------------------------------|--------------------------------|
| 懒加载 | `false`（默认） | 在 `ApplicationPreparedEvent` 时，**晚于**日志系统初始化      | 否                             |
| 饥饿   | `true`  | 在 `EnvironmentPostProcessor` 中，`application.yml` 加载后、**Logback 之前** | 是             |

- **饥饿阶段** —— `GitHubConfigEnvironmentPostProcessor` 总是很早运行（紧跟
  `ConfigDataEnvironmentPostProcessor` 之后，此时 `application.yml` 已可用），但只有在
  `eager = true` 时才在这里*加载*。此时加载发生在 Logback 初始化之前，因此 `logback-spring.xml`
  可以通过 `<springProperty>` 引用来自 GitHub 的值。
- **懒加载阶段** —— `GitHubConfigApplicationListener` 监听 `ApplicationPreparedEvent`
  （在日志系统就绪之后），当饥饿阶段没有加载时由它来加载。一个标记属性确保配置不会被加载两次。

无论哪种模式，配置都在 bean 创建之前进入 `Environment`，因此 `@Value` /
`@ConfigurationProperties` 始终能读到。两种模式的唯一区别在于 Logback 自身的配置能否读到这些值。

#### 设置开关

由于该开关是在一个后处理器方法*内部*从环境读取的（而不是在 `getOrder()` 中），因此它可以写在
`application.yml` 里：

```yaml
chieh:
  config:
    github:
      eager: true
```

系统属性或环境变量会覆盖 yml 中的值（适合临时运行）：

```bash
-Dchieh.config.github.eager=true          # 系统属性
export CHIEH_CONFIG_GITHUB_EAGER=true     # bash
$env:CHIEH_CONFIG_GITHUB_EAGER = "true"   # PowerShell
```

启动日志会显示实际运行的是哪个阶段，例如
`Loaded chieh-config from GitHub [eager mode]: ...` 或 `[lazy mode]`。

## 技术栈

- Spring Boot 3.3.x
- Spring Web
- Java 21
