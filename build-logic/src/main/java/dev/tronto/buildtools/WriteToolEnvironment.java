package dev.tronto.buildtools;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TreeMap;

/** An explicit environment for child processes; never changes the host's PATH or shell profile. */
@UntrackedTask(because = "Resolve current physical tool paths after preparation, including symlink changes")
public abstract class WriteToolEnvironment extends DefaultTask {
    @Input public abstract MapProperty<String, String> getVariables();
    @OutputFile public abstract RegularFileProperty getEnvironmentFile();
    @TaskAction public void write() throws Exception {
        var file = getEnvironmentFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        var variables = new TreeMap<>(getVariables().get());
        // Only producer child processes see these private vcpkg adapters. Existing
        // local installations and the user's PATH are never modified.
        if (!System.getProperty("os.name").startsWith("Windows") && variables.containsKey("VCPKG_ROOT")) {
            var bin = file.getParent().resolve("vcpkg-archive-bin");
            Files.createDirectories(bin);
            var script = bin.resolve("vcpkg_archive.py");
            try (var source = WriteToolEnvironment.class.getResourceAsStream("/dev/tronto/buildtools/vcpkg_archive.py")) {
                if (source == null) throw new java.io.IOException("Missing vcpkg archive adapters");
                Files.copy(source, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            var python = new ProcessBuilder(variables.getOrDefault("BUILD_TOOLS_PYTHON", "python3"), "-c", "import sys; print(sys.executable)").start();
            String executable = new String(python.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (python.waitFor() != 0 || executable.isEmpty()) throw new java.io.IOException("Cannot resolve configured Python executable");
            for (String tool : java.util.List.of("tar", "zip", "unzip")) {
                var launcher = bin.resolve(tool);
                String command = "#!/bin/sh\nexec " + shellQuote(executable) + " " + shellQuote(script.toFile().getCanonicalPath())
                    + " " + tool + " " + shellQuote(variables.get("CMAKE_COMMAND")) + " \"$@\"\n";
                Files.writeString(launcher, command, StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(launcher, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            variables.put("PATH_PREFIX", bin.toFile().getCanonicalPath() + java.io.File.pathSeparator + variables.getOrDefault("PATH_PREFIX", ""));
        }
        // A host CMake is already prepared by Gradle; don't download a duplicate.
        if (variables.containsKey("VCPKG_ROOT")) variables.put("VCPKG_FORCE_SYSTEM_BINARIES", "1");
        var lines = new java.util.ArrayList<String>();
        for (var entry : variables.entrySet()) {
            String value = entry.getValue();
            if (entry.getKey().equals("PATH_PREFIX")) {
                var parts = value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
                for (int i = 0; i < parts.length; i++) parts[i] = new java.io.File(parts[i]).getCanonicalPath();
                value = String.join(java.io.File.pathSeparator, parts);
            } else if (java.util.Set.of("CMAKE_COMMAND", "NINJA_COMMAND", "VCPKG_ROOT", "EMSDK", "EM_CONFIG", "EM_CACHE").contains(entry.getKey())) {
                value = new java.io.File(value).getCanonicalPath();
            }
            lines.add("  " + quote(entry.getKey()) + ": " + quote(value));
        }
        Files.writeString(file, "{\n" + String.join(",\n", lines) + "\n}\n", StandardCharsets.UTF_8);
    }
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
