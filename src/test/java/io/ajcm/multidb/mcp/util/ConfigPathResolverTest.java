package io.ajcm.multidb.mcp.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.ajcm.multidb.mcp.util.ConfigPathResolver.ConfigSource;
import io.ajcm.multidb.mcp.util.ConfigPathResolver.ResolvedConfigPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveUsesCliArgumentBeforeEnvironment() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("cwd"));
        Path applicationDirectory = Files.createDirectory(tempDir.resolve("app"));
        Path cliConfig = writeConfig(workingDirectory.resolve("cli-connections.json"));
        Path envConfig = writeConfig(workingDirectory.resolve("env-connections.json"));

        ResolvedConfigPath resolved = ConfigPathResolver.resolve(
            new String[] {"--connections-file", "cli-connections.json"},
            Map.of("CONNECTIONS_FILE", envConfig.toString()),
            workingDirectory,
            applicationDirectory
        );

        assertEquals(cliConfig, resolved.path());
        assertEquals(ConfigSource.CLI_ARGUMENT, resolved.source());
    }

    @Test
    void resolveUsesEnvironmentWhenCliArgumentIsAbsent() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("cwd"));
        Path applicationDirectory = Files.createDirectory(tempDir.resolve("app"));
        Path envConfig = writeConfig(workingDirectory.resolve("env-connections.json"));

        ResolvedConfigPath resolved = ConfigPathResolver.resolve(
            new String[0],
            Map.of("CONNECTIONS_FILE", "env-connections.json"),
            workingDirectory,
            applicationDirectory
        );

        assertEquals(envConfig, resolved.path());
        assertEquals(ConfigSource.ENVIRONMENT, resolved.source());
    }

    @Test
    void resolveFallsBackToApplicationDirectoryBeforeWorkingDirectory() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("cwd"));
        Path applicationDirectory = Files.createDirectory(tempDir.resolve("app"));
        Path applicationConfig = writeConfig(applicationDirectory.resolve("connections.json"));
        writeConfig(workingDirectory.resolve("connections.json"));

        ResolvedConfigPath resolved = ConfigPathResolver.resolve(
            new String[0],
            Map.of(),
            workingDirectory,
            applicationDirectory
        );

        assertEquals(applicationConfig, resolved.path());
        assertEquals(ConfigSource.APPLICATION_DIRECTORY, resolved.source());
    }

    @Test
    void resolveFallsBackToWorkingDirectoryWhenApplicationDirectoryHasNoConfig() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("cwd"));
        Path applicationDirectory = Files.createDirectory(tempDir.resolve("app"));
        Path workingDirectoryConfig = writeConfig(workingDirectory.resolve("connections.json"));

        ResolvedConfigPath resolved = ConfigPathResolver.resolve(
            new String[0],
            Map.of(),
            workingDirectory,
            applicationDirectory
        );

        assertEquals(workingDirectoryConfig, resolved.path());
        assertEquals(ConfigSource.WORKING_DIRECTORY, resolved.source());
    }

    @Test
    void resolveFailsWithActionableMessageWhenCliPathDoesNotExist() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("cwd"));
        Path applicationDirectory = Files.createDirectory(tempDir.resolve("app"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigPathResolver.resolve(
                new String[] {"--connections-file=missing.json"},
                Map.of(),
                workingDirectory,
                applicationDirectory
            )
        );

        assertTrue(exception.getMessage().contains("Configuration file from --connections-file not found"));
    }

    @Test
    void resolveFailsWithActionableMessageWhenNoConfigCanBeFound() throws Exception {
        Path workingDirectory = Files.createDirectory(tempDir.resolve("cwd"));
        Path applicationDirectory = Files.createDirectory(tempDir.resolve("app"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ConfigPathResolver.resolve(
                new String[0],
                Map.of(),
                workingDirectory,
                applicationDirectory
            )
        );

        assertTrue(exception.getMessage().contains("CONNECTIONS_FILE not set and no default configuration file was found"));
        assertTrue(exception.getMessage().contains("--connections-file <path>"));
    }

    private Path writeConfig(Path path) throws Exception {
        Files.writeString(path, "{\"connections\":[]}");
        return path;
    }
}
