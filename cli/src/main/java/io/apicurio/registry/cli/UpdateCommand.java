package io.apicurio.registry.cli;

import io.apicurio.registry.cli.common.AbstractCommand;
import io.apicurio.registry.cli.common.CliException;
import io.apicurio.registry.cli.services.CliVersion;
import io.apicurio.registry.cli.services.Update;
import io.apicurio.registry.cli.utils.FileUtils;
import io.apicurio.registry.cli.utils.OutputBuffer;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Command(
        name = "update",
        description = "Update the CLI to a newer version"
)
public class UpdateCommand extends AbstractCommand {

    private static final Logger log = Logger.getLogger(UpdateCommand.class);

    @Parameters(
            index = "0",
            arity = "0..1",
            description = "Target version to update to. If not provided, the latest unambiguous version is used."
    )
    private String targetVersion;

    @Option(
            names = {"--path"},
            description = "Install from a local zip file. Version check is not performed."
    )
    private Path path;

    @Option(
            names = {"--check"},
            description = "Check for available updates without installing.",
            defaultValue = "false"
    )
    private boolean check;

    @Option(
            names = {"--postpone"},
            description = "Postpone update notifications. Default: 120 hours (5 days).",
            arity = "0..1",
            defaultValue = "-1",
            fallbackValue = "120"
    )
    private int postponeHours;

    @ParentCommand
    private Acr parent;

    @Inject
    Update update;

    @Override
    public void run(OutputBuffer output) throws Exception {
        if (postponeHours >= 0 && check) {
            throw new CliException("Cannot use --postpone and --check together.");
        }

        if (postponeHours >= 0) {
            handlePostpone(output);
            return;
        }

        var currentVersion = config.getCliVersion();

        if (check) {
            handleCheck(output, currentVersion);
            return;
        }

        if (path != null) {
            handlePathInstall(output);
            return;
        }

        handleAutoUpdate(output, currentVersion);
    }

    private static final int MAX_POSTPONE_HOURS = 8760; // 1 year

    private void handlePostpone(OutputBuffer output) {
        var hours = Math.min(postponeHours, MAX_POSTPONE_HOURS);
        var configModel = config.read();
        var until = Instant.now().plusSeconds(hours * 3600L);
        configModel.getConfig().put("internal.update.postponed-until", until.toString());
        config.write(configModel);
        output.writeStdOutLine("Update notifications postponed for %d hours.".formatted(postponeHours));
    }

    private void handleCheck(OutputBuffer output, CliVersion currentVersion) {
        var result = update.checkForUpdates(currentVersion);

        if (!result.hasUpdates()) {
            output.writeStdOutLine("You are running the latest version (%s).".formatted(currentVersion));
            return;
        }

        output.writeStdOutChunk(out -> {
            result.formatMessage(out);
        });
    }

    private void handlePathInstall(OutputBuffer output) throws Exception {
        // Use the current binary's home (always set by the acr launcher) rather than ACR_HOME, which
        // is only exported into a shell for per-user installs; a global install has no sourced env.
        // For a per-user install the two resolve to the same directory, so existing behaviour is
        // unchanged; this only makes the download scratch dir resolvable for global installs too.
        var homePath = config.getAcrCurrentHomePath();
        var targetDir = homePath.resolve(UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(targetDir);
            var zipFilePath = targetDir.resolve(path.getFileName());
            Files.copy(path, zipFilePath, REPLACE_EXISTING);
            runInstallFromZip(zipFilePath, targetDir, output);
        } finally {
            FileUtils.deleteDirectory(targetDir);
        }
    }

    private void handleAutoUpdate(OutputBuffer output, CliVersion currentVersion) throws Exception {
        // See handlePathInstall: use the current binary's home so updates work for global installs
        // too. Equivalent to ACR_HOME for a per-user install, so the existing update path is unchanged.
        var homePath = config.getAcrCurrentHomePath();

        String versionToDownload;
        if (targetVersion != null) {
            versionToDownload = targetVersion;
        } else {
            var result = update.checkForUpdates(currentVersion);

            if (!result.hasUpdates()) {
                output.writeStdOutLine("You are running the latest version (%s).".formatted(currentVersion));
                return;
            }
            var unambiguous = result.unambiguousUpdate();
            if (unambiguous == null) {
                output.writeStdOutChunk(out -> {
                    out.append("Multiple update candidates available:\n");
                    result.formatMessage(out);
                    out.append("\nSpecify a version: acr update <version>\n");
                });
                return;
            }
            versionToDownload = unambiguous.toString();
            log.debugf("Auto-selected version: %s", versionToDownload);
        }

        output.writeStdOutLine("Updating to version %s...".formatted(versionToDownload));
        output.print();

        var targetDir = homePath.resolve(UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.createDirectories(targetDir);
            var zipFilePath = update.downloadVersion(versionToDownload, targetDir);
            runInstallFromZip(zipFilePath, targetDir, output);
        } finally {
            FileUtils.deleteDirectory(targetDir);
        }
    }

    private void runInstallFromZip(Path zipFilePath, Path targetDir, OutputBuffer output) throws Exception {
        FileUtils.unzip(zipFilePath, targetDir);

        Path acrPath = targetDir.resolve(InstallCommand.ACR_SCRIPT);
        Path acrRunnerPath = targetDir.resolve(InstallCommand.ACR_BINARY);
        for (var executable : List.of(acrPath, acrRunnerPath)) {
            if (executable.toFile().exists() && !executable.toFile().setExecutable(true, false)) {
                throw new CliException("Failed to set executable permission on " + executable);
            }
        }
        log.debugf("Running subprocess: %s", acrPath);
        var cmd = buildInstallCommand(acrPath);
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.inheritIO();
        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        log.debugf("Subprocess exited with code: %s", exitCode);
        if (exitCode != 0) {
            throw new CliException("Update failed with exit code: " + exitCode, exitCode);
        }

        output.writeStdOutLine("Update complete.");
    }

    /**
     * Builds the argument list for the {@code acr install} subprocess that performs the actual
     * update, forwarding {@code --global} when the current installation is global so the re-install
     * keeps the same scope.
     */
    List<String> buildInstallCommand(Path acrPath) {
        var cmd = new ArrayList<String>(4);
        cmd.add(acrPath.toString());
        cmd.add("install");
        if (isGlobalInstall()) {
            cmd.add("--global");
        }
        // parent is null only in unit tests that build the command directly (never during parsing).
        if (parent != null && parent.isVerbose()) {
            cmd.add("--verbose");
        }
        return cmd;
    }

    /**
     * Detects whether the current installation was performed globally, so the re-install triggered
     * by an update keeps the same system-wide scope. The marker is read via {@link Config#read()},
     * which resolves {@code config.json} under {@code getAcrCurrentHomePath()} (the running binary's
     * own home), so it correctly finds the marker a global install wrote even though {@code ACR_HOME}
     * is never exported for a global install. Best-effort: any failure to read the config (for
     * example a per-user install with no marker) is treated as a per-user installation.
     */
    private boolean isGlobalInstall() {
        try {
            return Boolean.parseBoolean(config.read().getConfig().get(InstallCommand.CONFIG_KEY_GLOBAL_INSTALL));
        } catch (RuntimeException e) {
            log.debugf("Could not determine install scope, assuming per-user: %s", e.getMessage());
            return false;
        }
    }

}
