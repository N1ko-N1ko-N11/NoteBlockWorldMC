package dev.example.noteblockworldmc.command;

import dev.example.noteblockworldmc.NoteblockWorldMCPlugin;
import dev.example.noteblockworldmc.api.NoteBlockWorldClient.Sort;
import dev.example.noteblockworldmc.api.NoteBlockWorldClient.SongQuery;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Parses free text + flags into a SongQuery. Shared by /nbw search and the
 * GUI search dialog so flag syntax behaves identically in both.
 *
 * Flags: --sort <recent|popular|random|title|duration|count>
 *        --order <asc|desc>  --category <n>  --downloaded
 *
 * There is no --page flag: every consumer is the GUI, which has page buttons.
 */
public final class QueryParser {

    public static final List<String> FLAGS =
            Arrays.asList("--sort", "--order", "--category", "--downloaded");
    public static final List<String> SORT_NAMES =
            Arrays.asList("recent", "popular", "random", "title", "duration", "count");

    private QueryParser() {
    }

    /**
     * Non-flag words become the free-text query.
     *
     * @return the parsed query, or null after messaging the sender about
     *         the problem
     */
    public static SongQuery parse(NoteblockWorldMCPlugin plugin, CommandSender sender,
                                  String[] args) {
        List<String> queryWords = new ArrayList<>();
        // The limit is left at its default: every consumer is the GUI, which
        // overrides it with its own slot count anyway.
        SongQuery.Builder builder = SongQuery.builder();

        for (int i = 0; i < args.length; i++) {
            String token = args[i];

            if (!token.startsWith("--")) {
                queryWords.add(token);
                continue;
            }

            String flag = token.toLowerCase(Locale.ROOT);
            if (!FLAGS.contains(flag)) {
                sender.sendMessage(plugin.getMessages().get("flags.unknown-flag",
                        Placeholder.unparsed("flag", token)));
                return null;
            }
            // --downloaded is a boolean switch: query the local library
            if (flag.equals("--downloaded")) {
                builder.downloadedOnly(true);
                continue;
            }
            // A following "--word" is the next flag, not this flag's value:
            // without this check, "--category --sort popular" silently set
            // category to the literal string "--sort".
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                sender.sendMessage(plugin.getMessages().get("flags.flag-needs-value",
                        Placeholder.unparsed("flag", token)));
                return null;
            }
            String value = args[++i];

            switch (flag) {
                case "--sort" -> {
                    Sort sort = Sort.fromUserInput(value);
                    if (sort == null) {
                        sender.sendMessage(plugin.getMessages().get("flags.unknown-sort",
                                Placeholder.unparsed("value", value),
                                Placeholder.unparsed("valid", String.join(", ", SORT_NAMES))));
                        return null;
                    }
                    builder.sort(sort);
                }
                case "--order" -> {
                    switch (value.toLowerCase(Locale.ROOT)) {
                        case "asc", "ascending" -> builder.ascending(true);
                        case "desc", "descending" -> builder.ascending(false);
                        default -> {
                            sender.sendMessage(plugin.getMessages().get("flags.order-invalid"));
                            return null;
                        }
                    }
                }
                case "--category" -> builder.category(value);
            }
        }

        builder.query(String.join(" ", queryWords));
        return builder.build();
    }
}
