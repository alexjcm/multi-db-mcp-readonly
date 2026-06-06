# Repository Guidelines

## Project Structure & Module Organization
Core Java sources live under `src/main/java/io/ajcm/multidb/mcp`. The main entry point is `Main.java`, with packages split by concern: `config/` for configuration management, `db/` for database connection providers, `tool/` for MCP tool builders, `util/` for shared helpers, and `validation/` for SQL guards. JSON schemas for MCP tool input/output live in `src/main/resources/schemas`, and GraalVM native-image metadata lives in `src/main/resources/META-INF/native-image`. Build artifacts are written to `build/`. A `src/test/java` tree exists for tests.

## Database Connection Provider Contract
All database implementations must implement the `DbConnectionProvider` interface:

```java
public interface DbConnectionProvider {
    boolean healthCheck();
    List<TableMetadata> listTables(String schema);
    TableMetadata describeTable(String schema, String table);
    String executeSelect(String query) throws Exception;
    ConnectionConfig getConfig();
    void close();
}
```

Key requirements:
- **Read-only enforcement**: Must validate queries before execution
- **Standardized metadata**: Return `TableMetadata` with consistent structure
- **Extended metadata**: Include database-specific information in `extended` field
- **Error handling**: Throw descriptive exceptions for connection/query failures

## Build, Test, and Development Commands
Use the Gradle wrapper from the repository root:

- `./gradlew test` runs the JUnit suite.
- `./gradlew clean shadowJar` builds the runnable fat JAR in `build/libs/`.
- `./gradlew clean nativeCompile` builds the GraalVM native binary in `build/native/nativeCompile/`.
- `java -jar build/libs/multi-db-mcp-readonly-*-all.jar` runs the server in JVM mode for local validation.

Set `CONNECTIONS_FILE` environment variable before running locally; use `connections.json.example` as the canonical template.

## Coding Style & Naming Conventions
Follow the existing Java style: 4-space indentation, braces on the same line, and package names rooted at `io.ajcm.multidb.mcp`. Keep classes in PascalCase, methods and fields in camelCase, and constants in UPPER_SNAKE_CASE. Match the current naming used by service classes such as `Db2ConnectionService` and keep public APIs documented with short Javadoc where behavior is not obvious. There is no dedicated formatter config in the repo, so keep imports tidy and changes consistent with neighboring code.

## Adding New Database Types
When adding support for a new database type:

1. **Add enum value** to `DbType.java`
2. **Create ConnectionService** implementing `DbConnectionProvider`
3. **Add dependency** to `build.gradle`
4. **Update Main.java** switch statement
5. **Add comprehensive tests** covering all tool operations
6. **Update documentation** in README.md

Reference `SingleStoreConnectionService.java` for implementation patterns.

## Testing Guidelines
Tests are configured with JUnit Jupiter via Gradle. Add new tests under `src/test/java`, mirroring the production package structure, and prefer class names ending in `Test` such as `ConfigLoaderTest`. Cover configuration validation, SQL validation, connection handling, and error paths when changing behavior. Run `./gradlew test` before opening a PR; if native behavior changes, also validate the `shadowJar` or `nativeCompile` path you touched.

## Configuration Management
All database connections are configured via `connections.json` with fail-fast validation:

- **Required fields**: `id`, `type`, `host`, `user`, `password`, `database`
- **Optional fields**: `port`, `ssl`, `description`
- **Timeouts**: `query_timeout_sec`, `login_timeout_sec`
- **Validation**: Server exits with error code 1 if configuration is invalid
