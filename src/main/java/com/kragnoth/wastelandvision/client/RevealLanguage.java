package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.vision.Vision;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * "What the vision lets you read": a vision folder may hold reveal/<lang>.json (falls back to reveal/en_us.json), a flat map of
 * translation keys to text. While that vision is on, those keys translate to the revealed text everywhere (books, signs,
 * tooltips); otherwise they show whatever the game would (usually the component's scrambled "fallback").
 * Done by wrapping the current Language: TranslatableContents re-resolves when the Language instance changes.
 */
final class RevealLanguage extends Language {
    private static Map<String, String> reveal = Map.of();

    final Language delegate;
    private final Map<String, String> map;

    private RevealLanguage(Language delegate, Map<String, String> map) {
        this.delegate = delegate;
        this.map = map;
    }

    /** Loads the vision's reveal map for the current language (called when a vision is compiled). */
    static void load(@Nullable Vision v) {
        reveal = Map.of();
        if (v == null) return;
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        Path dir = v.folder().resolve("reveal");
        Path f = dir.resolve(code + ".json");
        if (!Files.isRegularFile(f)) f = dir.resolve("en_us.json");
        if (!Files.isRegularFile(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            Map<String, String> m = new HashMap<>();
            for (var e : o.entrySet()) m.put(e.getKey(), e.getValue().getAsString());
            reveal = Map.copyOf(m);
        } catch (Exception ex) {
            WastelandClient.LOGGER.warn("Wasteland Vision: couldn't read {}: {}", f, ex.toString());
        }
    }

    /** Each client tick: wrap or unwrap the game's Language to match the vision state (re-wraps after a resource reload). */
    static void maintain() {
        Language cur = Language.getInstance();
        boolean want = WastelandState.active && !reveal.isEmpty();
        if (cur instanceof RevealLanguage rl) {
            if (!want) Language.inject(rl.delegate);
            else if (rl.map != reveal) Language.inject(new RevealLanguage(rl.delegate, reveal));
        } else if (want) {
            Language.inject(new RevealLanguage(cur, reveal));
        }
    }

    @Override
    public String getOrDefault(String key, String fallback) {
        String s = map.get(key);
        return s != null ? s : delegate.getOrDefault(key, fallback);
    }

    @Override
    public boolean has(String key) {
        return map.containsKey(key) || delegate.has(key);
    }

    @Override
    public boolean isDefaultRightToLeft() {
        return delegate.isDefaultRightToLeft();
    }

    @Override
    public FormattedCharSequence getVisualOrder(FormattedText text) {
        return delegate.getVisualOrder(text);
    }
}
