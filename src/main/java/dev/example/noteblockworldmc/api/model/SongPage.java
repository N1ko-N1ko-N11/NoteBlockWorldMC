package dev.example.noteblockworldmc.api.model;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;

/**
 * Mirrors Note Block World's paginated response, verified against the
 * backend source (song.service.ts returns exactly these fields):
 * { "content": [...], "page": 1, "limit": 10, "total": 1234 }
 * with page 1-based (their PageQueryDTO validates @Min(1)).
 */
public class SongPage {

    @SerializedName("content")
    private List<SongPreview> content;

    @SerializedName("page")
    private int page;

    @SerializedName("limit")
    private int limit;

    @SerializedName("total")
    private int total;

    /** Builds a page from local library data (downloaded songs). */
    public static SongPage of(List<SongPreview> content, int page, int limit, int total) {
        SongPage songPage = new SongPage();
        songPage.content = List.copyOf(content);   // immutable snapshot
        songPage.page = page;
        songPage.limit = limit;
        songPage.total = total;
        return songPage;
    }

    public List<SongPreview> getContent() {
        return content != null ? content : Collections.emptyList();
    }

    public int getPage() {
        return page;
    }

    public int getLimit() {
        return limit;
    }

    public int getTotal() {
        return total;
    }

    public int getTotalPages() {
        if (limit <= 0) return 1;
        return Math.max(1, (int) Math.ceil((double) total / limit));
    }

    public boolean hasNextPage() {
        // page >= 1 guards a malformed response: a missing "page" field
        // deserializes to 0, and 0 < totalPages would advertise a phantom
        // next page on an empty result.
        return page >= 1 && page < getTotalPages();
    }
}
