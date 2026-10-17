package dev.tronto.buildtools;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import javax.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The installer validates receipts under a filesystem lock, including for offline builds. */
@UntrackedTask(because = "Tools outlive clean and are validated atomically by the installer, not restored from Gradle's build cache")
public abstract class PrepareTool extends DefaultTask {
    @Input public abstract Property<String> getTool();
    @Input public abstract Property<String> getVersion();
    @Input public abstract Property<String> getHost();
    @Input public abstract Property<String> getDownloadUrl();
    @Input public abstract Property<String> getSha256();
    @Input public abstract Property<String> getRevision();
    @Input public abstract Property<String> getBinaryVersion();
    @Input public abstract Property<String> getBinaryDownloadUrl();
    @Input public abstract Property<String> getBinarySha256();
    @Input public abstract Property<String> getExecutablePath();
    @Input public abstract Property<String> getPythonExecutable();
    @Input public abstract Property<String> getGitExecutable();
    @Input public abstract Property<Boolean> getOffline();
    @Input public abstract Property<Boolean> getUseLocal();
    @Internal public abstract DirectoryProperty getDirectory();
    @Internal public abstract DirectoryProperty getInstallationDirectory();
    @Inject protected abstract ExecOperations getExecOperations();

    @TaskAction public void prepare() throws Exception {
        Path script = getTemporaryDir().toPath().resolve("prepare_tool.py");
        Files.createDirectories(script.getParent());
        try (InputStream source = PrepareTool.class.getResourceAsStream("/dev/tronto/buildtools/prepare_tool.py")) {
            if (source == null) throw new GradleException("Missing tool installer resource");
            Files.copy(source, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> command = new ArrayList<>(List.of(getPythonExecutable().get(), script.toString()));
        add(command, "tool", getTool().get());
        add(command, "version", getVersion().get());
        add(command, "host", getHost().get());
        add(command, "directory", getDirectory().get().getAsFile().getAbsolutePath());
        add(command, "installation", getInstallationDirectory().get().getAsFile().getAbsolutePath());
        add(command, "url", getDownloadUrl().get());
        add(command, "sha256", getSha256().get());
        add(command, "revision", getRevision().get());
        add(command, "binary-version", getBinaryVersion().get());
        add(command, "binary-url", getBinaryDownloadUrl().get());
        add(command, "binary-sha256", getBinarySha256().get());
        add(command, "executable", getExecutablePath().get());
        add(command, "git", getGitExecutable().get());
        if (getOffline().get()) command.add("--offline");
        if (getUseLocal().get()) command.add("--local");
        getExecOperations().exec(spec -> spec.commandLine(command)).assertNormalExitValue();
    }
    private static void add(List<String> command, String key, String value) {
        command.add("--" + key); command.add(value);
    }
}
