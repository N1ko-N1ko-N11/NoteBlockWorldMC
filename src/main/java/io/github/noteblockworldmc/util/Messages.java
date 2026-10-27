package io.github.noteblockworldmc.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads user-facing text from messages.yml so every chat message, GUI
 * label, and dialog string can be translated or restyled.
 * <p>
 * Strings use MiniMessage format (colors, decorations) with named
 * placeholders like {@code <title>}. Keys missing from the admin's file fall
 * back to the defaults embedded in the jar, so a partial translation
 * never breaks the plugin.
 */
public class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private record State(YamlConfiguration config, Map<String, Component> cache, Set<String> reported) {}
    private volatile State state;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() { install(prepareReload()); }
    public YamlConfiguration prepareReload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
            try (InputStream embedded = plugin.getResource("messages.yml")) {
                if (embedded != null) loaded.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(embedded, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) { throw new IllegalArgumentException("Could not read messages.yml; previous messages retained", e); }
        return loaded;
    }
    public void install(YamlConfiguration loaded) {
        state = new State(loaded, new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
    }

    /**
     * The raw configured string, with no MiniMessage parsing.
     * <p>
     * For values that are data rather than display text (a glyph ramp, a
     * symbol set). Going through Component and back would drag in the plain
     * serializer for no benefit, and would silently mangle any value that
     * happens to contain MiniMessage-looking punctuation.
     */
    public String getRaw(String key, String fallback) {
        String raw = state.config().getString(key);
        return raw == null ? fallback : raw;
    }

    /** Deserializes a message with the given placeholders. */
    public Component get(String key, TagResolver... resolvers) {
        State snapshot = state;
        boolean cacheable = resolvers.length == 0;
        if (cacheable) {
            Component cached = snapshot.cache().get(key);
            if (cached != null) return cached;
        }

        String raw = snapshot.config().getString(key);
        if (raw == null) {
            // An outdated messages.yml renders raw keys in chat, which looks
            // like a plugin bug rather than a stale file. Say so once, so the
            // fix (regenerate or add the key) is obvious.
            warnOnce(key, "messages.yml has no key '" + key
                    + "'; showing the key itself. Delete messages.yml to regenerate it.");
            return Component.text(key);
        }

        Component rendered = render(key, raw, resolvers);
        if (cacheable) snapshot.cache().put(key, rendered);
        return rendered;
    }

    /**
     * Parses one MiniMessage string, surviving a malformed one.
     * <p>
     * messages.yml is hand-edited, and a mistyped tag makes MiniMessage throw
     * an UNCHECKED exception — which would otherwise escape into a command
     * handler or an event callback and break the feature entirely. Falling
     * back to the literal text keeps the plugin working and names the key.
     */
    private Component render(String key, String raw, TagResolver... resolvers) {
        try {
            return MINI.deserialize(raw, resolvers);
        } catch (RuntimeException e) {
            warnOnce(key, "messages.yml key '" + key + "' is not valid MiniMessage ("
                    + e.getMessage() + "); showing it as plain text.");
            return Component.text(raw);
        }
    }

    private void warnOnce(String key, String message) {
        if (state.reported().add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    /**
     * Like {@link #get} but with Minecraft's implicit item-text italics
     * disabled — use for item names, lore, and inventory titles.
     */
    public Component item(String key, TagResolver... resolvers) {
        return get(key, resolvers).decoration(TextDecoration.ITALIC, false);
    }
}
