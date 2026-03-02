# CLAUDE.md

## Project Overview

A Maven plugin that performs EclipseLink static weaving without `persistence.xml` or `orm.xml`. Scans user-specified packages for `@Entity` classes and applies bytecode weaving at the `process-classes` phase.

## Build & Test Commands

```shell
# Run all tests
mvn test

# Build the plugin
mvn package

# Build without tests
mvn package -DskipTests

# Release build (requires GPG key)
mvn deploy -P release
```

## Architecture

### Source Files

- [WeaveMojo.java](src/main/java/net/unit8/maven/plugins/WeaveMojo.java) — Main Mojo. Builds the classpath, invokes EclipseLink's `EntityManagerSetupImpl.predeploy()` to obtain a `ClassTransformer`, then rewrites every `.class` file under `target/classes`.
- [ManagedClassScanner.java](src/main/java/net/unit8/maven/plugins/ManagedClassScanner.java) — Scans filesystem directories for `@Entity`-annotated classes. Does **not** scan inside JAR files.
- [LogWriter.java](src/main/java/net/unit8/maven/plugins/LogWriter.java) — Bridges EclipseLink's `PrintWriter`-based logging to Maven's `Log` interface. Buffers output and flushes one line at a time on newline.

### Key Design Decisions

- **No `persistence.xml`**: A synthetic `SEPersistenceUnitInfo` is constructed at runtime from the scanned entity list.
- **In-place weaving**: `source` and `target` both default to `${project.build.outputDirectory}`, so woven bytecode overwrites the originals.
- **Filesystem-only scan**: `ManagedClassScanner` resolves package URLs via `ClassLoader.getResources()` and descends only into `file:` URLs. JAR-internal classes are silently skipped.

## Code Conventions

- Java 11 source/target (`maven.compiler.release=11`)
- No `persistence.xml` or `orm.xml` involved — keep it that way
- `findClasses()` in `ManagedClassScanner` is package-private (not private) to allow subclassing in tests
- Raw types are prohibited — use generic types throughout
- Resources (`URLClassLoader`, streams) must be managed with try-with-resources

## Testing

Tests use JUnit Jupiter 5 and Mockito 5.

```shell
mvn test
```

### Test Structure

- `LogWriterTest` — Integration-style test using a real `ConsoleLogger` spy
- `LogWriterAdditionalTest` — Unit tests for all `write()` overloads and `flush()` edge cases; uses `mock(Log.class)` directly
- `ManagedClassScannerTest` — Uses fixture entity classes under `net.unit8.maven.plugins.fixture`; `findClasses()` is called directly (package-private)
- `WeaveMojoTest` — Tests `setLogLevel`, `transform`, `buildClassPath`, and `createPersistenceUnitInfo` via reflection for private field injection

### Test Fixtures

Entity classes for scanner tests live under:

```
src/test/java/net/unit8/maven/plugins/fixture/
  EntityA.java        — @Entity annotated
  NonEntity.java      — no annotation
  sub/EntityB.java    — @Entity in sub-package
```

### Java 25 Compatibility

Tests run on Java 25. Byte Buddy (used by Mockito) does not officially support Java 25, so the following JVM flag is set in `maven-surefire-plugin`:

```xml
<argLine>-Dnet.bytebuddy.experimental=true</argLine>
```

Do **not** mock `ClassLoader` directly — it causes a JVM crash on Java 25 (`moduleEntry.cpp` internal error). Use subclassing or `URLClassLoader` with real temp directories instead.

## Dependencies

| Artifact | Version | Scope | Notes |
| --- | --- | --- | --- |
| `org.eclipse.persistence:org.eclipse.persistence.jpa` | 4.0.8 | compile | Core weaving engine |
| `org.apache.maven:maven-plugin-api` | 3.9.9 | provided | CVE-2021-26291 fix requires ≥ 3.8.1 |
| `org.apache.maven:maven-core` | 3.9.9 | provided | Same as above |
| `org.junit.jupiter:junit-jupiter` | 5.12.0 | test | |
| `org.mockito:mockito-core` | 5.16.1 | test | `mockito-inline` merged into core in v5 |

## Known Constraints

- **JAR scanning not supported**: Only filesystem directories are scanned. Entity classes inside dependency JARs cannot be woven.
- **Single persistence unit**: The synthetic PU is always named `"for-weaving"`. Multiple PU configurations are not supported.
- **`source` and `target` are identical**: Both parameters bind to `${project.build.outputDirectory}`. Weaving is always in-place.
