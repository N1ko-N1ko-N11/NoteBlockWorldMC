package io.github.noteblockworldmc.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Selects import files without allowing a command to escape the import folder. */
public final class ImportFiles {
    private ImportFiles() {}

    public static List<Path> select(Path directory, String requested) throws IOException {
        if (directory == null) throw new IOException("Import folder is unavailable");
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Import folder must not be a symbolic link");
        }
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Import folder is unavailable");
        }
        if (requested != null && !requested.isBlank()) {
            String name = requested.trim();
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1);
            }
            // A filename can contain spaces, but not directory components or links.
            if (name.contains("/") || name.contains("\\") || name.contains(":")
                    || !name.toLowerCase(Locale.ROOT).endsWith(".nbs")) {
                throw new IOException("Choose a .nbs filename directly inside the import folder");
            }
            Path file;
            try { file = directory.resolve(name); }
            catch (java.nio.file.InvalidPathException e) { throw new IOException("Invalid import filename", e); }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("No .nbs file named '" + name + "' in the import folder");
            }
            return List.of(file);
        }
        try (var files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbs"))
                    .sorted().toList();
        }
    }
}
