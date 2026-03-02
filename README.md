# staticweave-nopu-maven-plugin

A Maven plugin for EclipseLink Static Weaving without `persistence.xml` or `orm.xml`.

EclipseLink's standard static weaving tool (`StaticWeaveProcessor`) requires a `persistence.xml`, but this plugin allows you to specify target packages directly and perform weaving without any persistence unit descriptor files.

## Background

EclipseLink features such as lazy loading (`@Basic(fetch = FetchType.LAZY)`) and change tracking only take effect through bytecode weaving. Dynamic weaving (at runtime) requires application server configuration and is difficult to use in SE environments.

This plugin applies static weaving at build time, enabling these features without any runtime configuration.

## Requirements

- Java 11 or later
- Maven 3.8.1 or later
- EclipseLink 4.x (Jakarta Persistence 3.x)

## Usage

### Basic Configuration

```xml
<plugin>
  <groupId>net.unit8.maven.plugins</groupId>
  <artifactId>staticweave-nopu-maven-plugin</artifactId>
  <version>0.2.0</version>
  <executions>
    <execution>
      <goals>
        <goal>weave</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <packages>
      <package>com.example.entity</package>
    </packages>
  </configuration>
</plugin>
```

### Multiple Packages

```xml
<configuration>
  <packages>
    <package>com.example.entity</package>
    <package>com.example.model</package>
  </packages>
</configuration>
```

### Changing the Log Level

You can change the log output level for the weaving process.

```xml
<configuration>
  <packages>
    <package>com.example.entity</package>
  </packages>
  <logLevel>FINE</logLevel>
</configuration>
```

Or specify it on the Maven command line:

```shell
mvn staticweave-nopu:weave -Dweave.logLevel=FINE
```

Valid levels: `OFF`, `SEVERE`, `WARNING`, `INFO`, `CONFIG`, `FINE`, `FINER`, `FINEST`, `ALL` (default: `ALL`)

## Goal

### `weave`

| Attribute | Value |
| --- | --- |
| Default phase | `process-classes` |
| Dependency resolution scope | `compile` |

Scans the specified packages, applies weaving to all classes annotated with `@Entity`, and writes the results to `target/classes`.

#### Parameters

| Parameter | Type | Required | Default | Description |
| --- | --- | --- | --- | --- |
| `packages` | `List<String>` | Yes | - | Package names to scan (multiple values supported) |
| `logLevel` | `String` | No | `ALL` | EclipseLink log level |

## How It Works

1. Scans the packages specified in `packages` and detects classes annotated with `@Entity`.
2. Builds a temporary `SEPersistenceUnitInfo` from the detected classes (no `persistence.xml` involved).
3. Calls `EntityManagerSetupImpl.predeploy()` to obtain a class transformer from EclipseLink.
4. Applies the transformer to every class file under `target/classes` and overwrites them with the woven bytecode.

This plugin is designed to weave your own project's entity classes compiled into `target/classes`. Scanning inside dependency JAR files is out of scope.

## Dependencies

| Library | Version | Scope |
| --- | --- | --- |
| EclipseLink JPA | 4.0.8 | compile |
| Apache Maven Plugin API | 3.9.9 | provided |
| Apache Maven Core | 3.9.9 | provided |

## Build & Test

```shell
mvn test
mvn package
```

## License

Copyright © 2019 kawasima

Distributed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
