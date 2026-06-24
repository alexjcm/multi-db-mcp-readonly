package io.ajcm.multidb.mcp.util;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Map;

import io.ajcm.multidb.mcp.Main;

/**
 * Resolves the configuration file path used at startup.
 */
public final class ConfigPathResolver {
    static final String CONFIG_ARGUMENT = "--connections-file";
    static final String CONFIG_ENV_VAR = "CONNECTIONS_FILE";
    static final String DEFAULT_CONFIG_FILE = "connections.json";

    private ConfigPathResolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Resolves the configuration file using CLI arguments, environment variables,
     * and safe fallbacks.
     *
     * @param args application arguments
     * @return resolved configuration path metadata
     */
    public static ResolvedConfigPath resolve(String[] args) {
        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        Path applicationDirectory = findApplicationDirectory();
        return resolve(args, System.getenv(), workingDirectory, applicationDirectory);
    }

    static ResolvedConfigPath resolve(
        String[] args,
        Map<String, String> environment,
        Path workingDirectory,
        Path applicationDirectory
    ) {
        String cliPath = findCliConfigPath(args);
        if (cliPath != null) {
            return resolveExplicitPath(cliPath, workingDirectory, ConfigSource.CLI_ARGUMENT, CONFIG_ARGUMENT);
        }

        String envPath = environment.get(CONFIG_ENV_VAR);
        if (envPath != null) {
            if (envPath.isBlank()) {
                throw new IllegalArgumentException(
                    "CONNECTIONS_FILE is set but blank. Set it to a valid configuration file path "
                        + "or use --connections-file <path>."
                );
            }
            return resolveExplicitPath(envPath, workingDirectory, ConfigSource.ENVIRONMENT, CONFIG_ENV_VAR);
        }

        Path applicationCandidate = applicationDirectory != null
            ? applicationDirectory.resolve(DEFAULT_CONFIG_FILE).normalize()
            : null;
        if (applicationCandidate != null && Files.exists(applicationCandidate)) {
            return new ResolvedConfigPath(applicationCandidate, ConfigSource.APPLICATION_DIRECTORY);
        }

        Path workingDirectoryCandidate = workingDirectory.resolve(DEFAULT_CONFIG_FILE).normalize();
        if (Files.exists(workingDirectoryCandidate)) {
            return new ResolvedConfigPath(workingDirectoryCandidate, ConfigSource.WORKING_DIRECTORY);
        }

        String applicationLocation = applicationCandidate != null
            ? applicationCandidate.toString()
            : "unavailable";
        throw new IllegalArgumentException(
            "CONNECTIONS_FILE not set and no default configuration file was found. "
                + "Looked for 'connections.json' next to the running application at '"
                + applicationLocation
                + "' and in the current working directory '"
                + workingDirectoryCandidate
                + "'. Set CONNECTIONS_FILE to an absolute path or pass --connections-file <path>."
        );
    }

    private static ResolvedConfigPath resolveExplicitPath(
        String configuredPath,
        Path workingDirectory,
        ConfigSource source,
        String sourceLabel
    ) {
        if (configuredPath.isBlank()) {
            throw new IllegalArgumentException(sourceLabel + " is blank. Provide a valid configuration file path.");
        }

        Path resolvedPath = resolvePath(configuredPath, workingDirectory);
        if (!Files.exists(resolvedPath)) {
            throw new IllegalArgumentException(
                "Configuration file from "
                    + sourceLabel
                    + " not found: "
                    + resolvedPath
                    + ". Update the path to a valid connections JSON file."
            );
        }

        return new ResolvedConfigPath(resolvedPath, source);
    }

    private static Path resolvePath(String configuredPath, Path workingDirectory) {
        Path path = Paths.get(configuredPath);
        if (!path.isAbsolute()) {
            path = workingDirectory.resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    private static String findCliConfigPath(String[] args) {
        if (args == null) {
            return null;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (CONFIG_ARGUMENT.equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(
                        "Missing value for --connections-file. Usage: --connections-file <path>"
                    );
                }
                return args[i + 1];
            }
            if (arg != null && arg.startsWith(CONFIG_ARGUMENT + "=")) {
                return arg.substring((CONFIG_ARGUMENT + "=").length());
            }
        }

        return null;
    }

    private static Path findApplicationDirectory() {
        Path codeSourceLocation = findCodeSourceLocation();
        if (codeSourceLocation != null) {
            if (Files.isRegularFile(codeSourceLocation)) {
                return codeSourceLocation.getParent();
            }
            if (Files.isDirectory(codeSourceLocation)) {
                return codeSourceLocation;
            }
        }

        Path commandPath = findCommandPath();
        if (commandPath != null && Files.isRegularFile(commandPath)) {
            return commandPath.getParent();
        }

        return null;
    }

    private static Path findCodeSourceLocation() {
        try {
            CodeSource codeSource = Main.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            return Paths.get(codeSource.getLocation().toURI()).normalize().toAbsolutePath();
        } catch (URISyntaxException | SecurityException | UnsupportedOperationException _) {
            return null;
        }
    }

    private static Path findCommandPath() {
        return ProcessHandle.current().info().command()
            .map(command -> Paths.get(command).normalize().toAbsolutePath())
            .orElse(null);
    }

    /**
     * Source used to resolve the active configuration file.
     */
    public enum ConfigSource {
        CLI_ARGUMENT,
        ENVIRONMENT,
        APPLICATION_DIRECTORY,
        WORKING_DIRECTORY
    }

    /**
     * Resolved configuration path and its source.
     *
     * @param path resolved configuration path
     * @param source source used to resolve the path
     */
    public record ResolvedConfigPath(Path path, ConfigSource source) {
    }
}
