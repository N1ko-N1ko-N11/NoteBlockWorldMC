package io.github.noteblockworldmc.util;

import org.bukkit.configuration.file.FileConfiguration;
import java.net.URI;

/** Validate the entire proposal before any service observes it. */
public final class SettingsValidator {
    private SettingsValidator() {}
    public static void validate(FileConfiguration c) {
        for (var entry : c.getValues(true).entrySet()) {
            if (entry.getValue() instanceof Number n && !Double.isFinite(n.doubleValue()))
                throw new IllegalArgumentException(entry.getKey() + " must be finite");
        }
        URI api = URI.create(c.getString("api-base-url", "https://api.noteblock.world/v1").trim());
        if (api.getHost() == null || api.getUserInfo() != null || api.getRawQuery() != null || api.getRawFragment() != null
                || !("https".equalsIgnoreCase(api.getScheme()) || "http".equalsIgnoreCase(api.getScheme())))
            throw new IllegalArgumentException("api-base-url must be an absolute HTTP(S) URL without credentials, query or fragment");
        number(c,"http.connect-timeout",10,1,120,true); number(c,"http.request-timeout",15,1,120,true);
        number(c,"download.max-zip-size-mb",32,1,128,true); number(c,"download.max-extracted-size-mb",64,1,256,true);
        number(c,"download.max-stored-songs",500,0,100_000,true); number(c,"download.max-concurrent-downloads",4,1,16,true);
        number(c,"resource-pack.port",8194,1,65535,true); packFormat(c);
        number(c,"resource-pack.max-custom-sounds-mb",64,0,64,true);
        number(c,"resource-pack.max-concurrent-downloads",16,1,64,true);
        number(c,"resource-pack.rate-limit-per-minute",30,0,100_000,true);
        number(c,"resource-pack.rebuild-debounce-seconds",3,0,60,true);
        number(c,"resource-pack.settle-delay-ms",2000,0,30_000,true);
        number(c,"playback.actionbar-length",20,4,40,true); number(c,"playback.precision-ms",2,2,25,true);
        number(c,"playback.fade-out-ms",500,0,5000,true); number(c,"playback.limiter-threshold",8,2,8192,true);
        number(c,"playback.audible-floor",0,0,1,false);
        number(c,"playback.client-channels",247,16,8192,true); number(c,"playback.voice-ceiling",200,16,8192,true);
        number(c,"playback.humanize-timing-ms",6,0,50,false); number(c,"playback.humanize-velocity",.04,0,.5,false);
        number(c,"playback.humanize-percussion-factor",.25,0,1,false); number(c,"playback.humanize-drift",.7,0,1,false);
        number(c,"playback.pan-distance",2,.1,8,false);
        for (String key : new String[]{"gui.click-sound", "resource-pack.enabled", "resource-pack.kick-on-refuse",
                "resource-pack.repush-on-remove", "resource-pack.download-vanilla-sounds", "resource-pack.recover-vanilla-custom-sounds",
                "playback.show-actionbar", "playback.respect-loop", "playback.high-precision", "playback.soft-limiter",
                "playback.voice-allocation", "playback.humanize", "playback.follow-player", "playback.stereo-panning"}) {
            Object value = c.get(key); if (value != null && !(value instanceof Boolean))
                throw new IllegalArgumentException(key + " must be true or false");
        }
    }
    /** "auto" follows the server's Minecraft version; otherwise an explicit format number. */
    private static void packFormat(FileConfiguration c) {
        Object raw = c.get("resource-pack.pack-format");
        if (raw instanceof String text && text.trim().equalsIgnoreCase("auto")) return;
        if (raw != null && !(raw instanceof Number))
            throw new IllegalArgumentException("resource-pack.pack-format must be auto or an integer between 1 and 9999");
        number(c, "resource-pack.pack-format", 97, 1, 9999, true);
    }
    private static void number(FileConfiguration c, String key, double fallback, double min, double max, boolean integer) {
        Object raw = c.get(key); double value = raw == null ? fallback : raw instanceof Number n ? n.doubleValue() : Double.NaN;
        if (!Double.isFinite(value) || value < min || value > max || integer && value != Math.rint(value))
            throw new IllegalArgumentException(key + " must be " + (integer ? "an integer" : "a number") + " between " + min + " and " + max);
    }
}
