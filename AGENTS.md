# Repository Guidelines

## Project Structure & Module Organization
Core Java sources live under `src/main/java/io/ajcm/db2/ibmi/mcp`. The main entry point is `Main.java`, with packages split by concern: `tools/` for MCP tool handlers, `db/` for DB2 connection logic, `validation/` for SQL guards, and `util/` for shared helpers. JSON schemas for MCP tool input/output live in `src/main/resources/schemas`, and GraalVM native-image metadata lives in `src/main/resources/META-INF/native-image`. Build artifacts are written to `build/`. A `src/test/java` tree exists for tests, but this repository currently has no committed test classes.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repository root:

- `./gradlew test` runs the JUnit suite.
- `./gradlew clean shadowJar` builds the runnable fat JAR in `build/libs/`.
- `./gradlew clean nativeCompile` builds the GraalVM native binary in `build/native/nativeCompile/`.
- `java -jar build/libs/db2-ibmi-mcp-readonly-1.0.0-all.jar` runs the server in JVM mode for local validation.

Set DB2 connection variables before running locally; use `.env.example` as the canonical template.

## Coding Style & Naming Conventions
Follow the existing Java style: 4-space indentation, braces on the same line, and package names rooted at `io.ajcm.db2.ibmi.mcp`. Keep classes in PascalCase, methods and fields in camelCase, and constants in UPPER_SNAKE_CASE. Match the current naming used by tool classes such as `ExecuteSelectTool` and keep public APIs documented with short Javadoc where behavior is not obvious. There is no dedicated formatter config in the repo, so keep imports tidy and changes consistent with neighboring code.

## Testing Guidelines
Tests are configured with JUnit Jupiter via Gradle. Add new tests under `src/test/java`, mirroring the production package structure, and prefer class names ending in `Test` such as `DbClientTest`. Cover SQL validation, connection ID normalization, and error handling paths when changing tool behavior. Run `./gradlew test` before opening a PR; if native behavior changes, also validate the `shadowJar` or `nativeCompile` path you touched.
