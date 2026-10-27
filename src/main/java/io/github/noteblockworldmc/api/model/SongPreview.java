package io.github.noteblockworldmc.api.model;

import com.google.gson.annotations.SerializedName;

import java.util.regex.Pattern;

/**
 * Mirrors Note Block World's SongPreviewDto.
 * Only the fields we care about are mapped; Gson ignores the rest.
 */
public class SongPreview {

    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

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
                ? "" : io.github.noteblockworldmc.util.Limits.display(uploader.getUsername());
    }

    public String getPublicId() {
        return publicId;
    }

    /** Catalog records without a usable ID must never become selectable items. */
    public boolean hasValidPublicId() {
        return publicId != null && PUBLIC_ID.matcher(publicId).matches();
    }

    public String getTitle() {
        return title == null ? "" : io.github.noteblockworldmc.util.Limits.display(sanitize(title));
    }

    /** Sanitized description, "" if the API omitted it (same contract as getTitle). */
    public String getDescription() {
        // Titles and authors already drop the section sign; descriptions kept
        // it, so uploaded text could recolour or obfuscate (§k) lore and chat.
        return description == null ? "" : sanitize(description.substring(0, Math.min(4096, description.length())))
                .replace('§', ' ');
    }

    /** Precompiled: sanitize runs per title per GUI render and per search hit. */
    private static final Pattern STRIP_CONTROL =
            Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\n]]");

    /**
     * Cleans text coming from the web API for safe rendering in Minecraft chat.
     * <p>
     * Normalizes Windows (\r\n) and old Mac (\r) line endings to \n, then
     * strips other control and format characters such as zero-width spaces.
     */
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
            return io.github.noteblockworldmc.util.Limits.display(sanitize(originalAuthor));
        }
        if (uploader != null && uploader.getUsername() != null) {
            return io.github.noteblockworldmc.util.Limits.display(sanitize(uploader.getUsername()));
        }
        return "Unknown";
    }

    public double getDuration() {
        return Double.isFinite(duration) ? Math.max(0, duration) : 0;
    }

    /** Formats the duration as m:ss. */
    public String getFormattedDuration() {
        int total = (int) Math.max(0, Math.round(getDuration()));
        return String.format("%d:%02d", total / 60, total % 60);
    }

    public int getNoteCount() {
        return Math.max(0, noteCount);
    }

    public int getPlayCount() {
        return Math.max(0, playCount);
    }

}
