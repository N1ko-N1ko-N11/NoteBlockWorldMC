package io.github.noteblockworldmc.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Resolves an existing custom sample, retaining directories and rejecting escapes. */
public final class CustomSoundPaths {
    private CustomSoundPaths() {}

    public static Path resolve(Path soundsDirectory, String declared) throws IOException {
        if (declared == null || declared.isBlank() || declared.length() > 512 || !Files.isDirectory(soundsDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
        String name = declared.replace('\\', '/').trim();
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        try {
            Path root = soundsDirectory.toRealPath();
            Path candidate = root.resolve(name).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) return null;
            // A symlink inside sounds/ must not point outside that directory.
            Path real = candidate.toRealPath();
            return real.startsWith(root) ? real : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
