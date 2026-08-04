package dev.poleszczuk.ticksentry.config;

/**
 * A way for the analysis code to ask for translated text without depending on a server.
 *
 * <p>The classes that write the advice sentences - {@code HotspotAnalyzer},
 * {@code MemoryAnalyzer}, {@code PluginReport}, {@code ChunkLoadVerdict} - are deliberately pure
 * so their reasoning can be unit tested. Handing them a {@link MessageBundle} would drag Bukkit
 * in and take that away, so they take this instead.</p>
 *
 * <p>{@link #find} returns {@code null} when a key has no translation, and every caller keeps
 * its English sentence as the fallback. That is what makes the English live in exactly one place:
 * the code. {@code messages.yml} documents the keys and the placeholders each one accepts, and a
 * translator adds only the ones they want to change - so there is no second copy of the English
 * to drift out of step with the first.</p>
 */
@FunctionalInterface
public interface Messages {

    /**
     * Looks up a translated sentence.
     *
     * @param key          dotted key, for example {@code advice.mob-farm}
     * @param replacements alternating placeholder names and values, without the braces
     * @return the translated text, or {@code null} when the key has no translation
     */
    String find(String key, String... replacements);

    /**
     * @return a lookup that never finds anything, so every caller uses its built-in English.
     *         This is what the unit tests use, and what production falls back to when
     *         {@code messages.yml} says nothing about a key.
     */
    static Messages none() {
        return (key, replacements) -> null;
    }
}
