package dev.example.noteblockworldmc.api.model;

import com.google.gson.annotations.SerializedName;

import java.util.regex.Pattern;

/**
 * Mirrors Note Block World's SongPreviewDto.
 * Only the fields we care about are mapped; Gson ignores the rest.
 */
public class SongPreview {

    @SerializedName("publicId")
    private String publicId;

    @SerializedName("title")
    private String title;

    @SerializedName("description")
    private String description;

    @SerializedName("originalAuthor")
    private String originalAuthor;

    @SerializedName("uploader")
    private Uploader uploader;

    /** Duration in seconds. */
    @SerializedName("duration")
    private double duration;

    @SerializedName("noteCount")
    private int noteCount;

    @SerializedName("playCount")
    private int playCount;


    public static class Uploader {
        @SerializedName("username")
        private String username;

        public String getUsername() {
            return username;
        }
    }

    /** Builds a preview from local library data (downloaded songs). */
    public static SongPreview local(String publicId, String title, String author,
                                    double duration, int noteCount, int playCount,
                                    String uploaderName) {
        SongPreview preview = new SongPreview();
        if (uploaderName != null && !uploaderName.isBlank()) {
            preview.uploader = new Uploader();
            preview.uploader.username = uploaderName;
        }
        preview.publicId = publicId;
        preview.title = title;
        preview.originalAuthor = author;
        preview.duration = duration;
        preview.noteCount = noteCount;
        preview.playCount = playCount;
        return preview;
    }

    /** The account that uploaded this song ("" if unknown). */
    public String getUploaderName() {
        return uploader == null || uploader.getUsername() == null
                ? "" : uploader.getUsername();
    }

    public String getPublicId() {
        return publicId;
    }

    public String getTitle() {
        return title == null ? "" : sanitize(title);
    }

    /** Sanitized description, "" if the API omitted it (same contract as getTitle). */
    public String getDescription() {
        return description == null ? "" : sanitize(description);
    }

    /**
     * Cleans text coming from the web API for safe rendering in Minecraft chat.
     *
     * - Normalizes Windows (\r\n) and old Mac (\r) line endings to \n.
     *   Minecraft's font has no glyph for carriage return, so raw \r shows
     *   up as a "[CR]" box in chat.
     * - Strips all other control (Cc) and format (Cf) characters, e.g.
     *   zero-width spaces, which also render as missing-glyph boxes.
     */
    /** Precompiled: sanitize runs per title per GUI render and per search hit. */
    private static final Pattern STRIP_CONTROL =
            Pattern.compile("[[\\p{Cc}\\p{Cf}]&&[^\n]]");

    static String sanitize(String input) {
        if (input == null) return null;
        String normalized = input.replace("\r\n", "\n").replace('\r', '\n');
        return STRIP_CONTROL.matcher(normalized).replaceAll("");
    }

    /**
     * Prefer the credited original author; fall back to the uploader's username.
     */
    public String getDisplayAuthor() {
        if (originalAuthor != null && !originalAuthor.isBlank()) {
            return sanitize(originalAuthor);
        }
        if (uploader != null && uploader.getUsername() != null) {
            return sanitize(uploader.getUsername());
        }
        return "Unknown";
    }

    public double getDuration() {
        return duration;
    }

    /** Formats the duration as m:ss. */
    public String getFormattedDuration() {
        int total = (int) Math.max(0, Math.round(duration));
        return String.format("%d:%02d", total / 60, total % 60);
    }

    public int getNoteCount() {
        return noteCount;
    }

    public int getPlayCount() {
        return playCount;
    }

}
