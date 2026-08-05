package io.github.noteblockworldmc.util;

import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;

/**
 * Helpers for reporting the real reason a task failed.
 *
 * Async work here is wrapped twice before it reaches a callback: a checked
 * IOException is rethrown as an UncheckedIOException so it can escape a lambda,
 * and CompletableFuture then wraps THAT in a CompletionException. Unwrapping a
 * single level therefore lands on the UncheckedIOException, whose getMessage()
 * is the stringified inner exception -- which is how a player ends up reading
 * "java.nio.file.NoSuchFileException: /songs/.../song.nbs" in chat.
 */
public final class Throwables {

    private Throwables() {
    }

    /**
     * Walks to the deepest cause.
     *
     * Guards against a self-referencing cause and caps the depth, because a
     * malformed exception chain must not hang the thread reporting an error.
     */
    public static Throwable rootCause(Throwable throwable) {
        if (throwable == null) return null;
        Throwable current = throwable;
        for (int depth = 0; depth < 16; depth++) {
            Throwable next = current.getCause();
            if (next == null || next == current) break;
            current = next;
        }
        return current;
    }

    /** True when the failure was ultimately "that file is not there". */
    public static boolean isMissingFile(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root instanceof NoSuchFileException || root instanceof FileNotFoundException;
    }

    /**
     * A human-readable reason for display.
     *
     * Falls back to the exception's simple class name when the message is null
     * (common for NullPointerException and friends), so the player never sees
     * a bare "null".
     */
    public static String describe(Throwable throwable) {
        Throwable root = rootCause(throwable);
        if (root == null) return "unknown error";
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : message;
    }
}
