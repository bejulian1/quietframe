package no.julian.quietframe.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public record QuietFrameConfig(
        boolean enabled,
        @SerializedName("unfocused_fps") int unfocusedFps,
        @SerializedName("minimized_fps") int minimizedFps,
        @SerializedName("afk_enabled") boolean afkEnabled,
        @SerializedName("afk_timeout_seconds") int afkTimeoutSeconds,
        @SerializedName("afk_fps") int afkFps,
        @SerializedName("hover_wakeup") boolean hoverWakeup,
        @SerializedName("skip_render_when_minimized") boolean skipRenderWhenMinimized,
        @SerializedName("audio_attenuation") boolean audioAttenuation,
        @SerializedName("unfocused_volume_multiplier") float unfocusedVolumeMultiplier,
        @SerializedName("minimized_volume_multiplier") float minimizedVolumeMultiplier,
        @SerializedName("show_status_badge") boolean showStatusBadge,
        @SerializedName("pause_on_lost_focus_override") boolean pauseOnLostFocusOverride
) {
    public static final QuietFrameConfig DEFAULTS = new QuietFrameConfig(
            true, 10, 1, true, 60, 15, true, false, true, 0.25f, 0, false, false);
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();

    public QuietFrameConfig {
        requireRange("unfocused_fps", unfocusedFps, 1, 260);
        requireRange("minimized_fps", minimizedFps, 1, 260);
        requireRange("afk_timeout_seconds", afkTimeoutSeconds, 1, 86_400);
        requireRange("afk_fps", afkFps, 1, 260);
        validateVolume("unfocused_volume_multiplier", unfocusedVolumeMultiplier);
        validateVolume("minimized_volume_multiplier", minimizedVolumeMultiplier);
    }

    public QuietFrameConfig toggled() {
        return new QuietFrameConfig(!enabled, unfocusedFps, minimizedFps, afkEnabled, afkTimeoutSeconds, afkFps,
                hoverWakeup, skipRenderWhenMinimized, audioAttenuation, unfocusedVolumeMultiplier,
                minimizedVolumeMultiplier, showStatusBadge, pauseOnLostFocusOverride);
    }

    public QuietFrameConfig withProfile(boolean quiet) {
        return new QuietFrameConfig(enabled, quiet ? 5 : DEFAULTS.unfocusedFps, DEFAULTS.minimizedFps,
                afkEnabled, DEFAULTS.afkTimeoutSeconds, quiet ? 10 : DEFAULTS.afkFps, hoverWakeup,
                skipRenderWhenMinimized, audioAttenuation, quiet ? 0.10f : DEFAULTS.unfocusedVolumeMultiplier,
                DEFAULTS.minimizedVolumeMultiplier, showStatusBadge, pauseOnLostFocusOverride);
    }

    public static QuietFrameConfig load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonReader input = GSON.newJsonReader(reader);
            JsonObject json = readUniqueObject(input);
            if (input.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Unexpected content after the configuration object");
            }
            QuietFrameConfig defaults = DEFAULTS;
            return new QuietFrameConfig(
                    bool(json, "enabled", defaults.enabled),
                    integer(json, "unfocused_fps", defaults.unfocusedFps),
                    integer(json, "minimized_fps", defaults.minimizedFps),
                    bool(json, "afk_enabled", defaults.afkEnabled),
                    integer(json, "afk_timeout_seconds", defaults.afkTimeoutSeconds),
                    integer(json, "afk_fps", defaults.afkFps),
                    bool(json, "hover_wakeup", defaults.hoverWakeup),
                    bool(json, "skip_render_when_minimized", defaults.skipRenderWhenMinimized),
                    bool(json, "audio_attenuation", defaults.audioAttenuation),
                    volume(json, "unfocused_volume_multiplier", defaults.unfocusedVolumeMultiplier),
                    volume(json, "minimized_volume_multiplier", defaults.minimizedVolumeMultiplier),
                    bool(json, "show_status_badge", defaults.showStatusBadge),
                    bool(json, "pause_on_lost_focus_override", defaults.pauseOnLostFocusOverride));
        } catch (NoSuchFileException error) {
            return DEFAULTS;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | ArithmeticException error) {
            throw new IOException("Invalid QuietFrame configuration", error);
        }
    }

    private static JsonObject readUniqueObject(JsonReader input) throws IOException {
        JsonObject object = new JsonObject();
        input.beginObject();
        while (input.hasNext()) {
            String name = input.nextName();
            if (object.has(name)) {
                throw new IOException("Duplicate JSON key at " + input.getPath());
            }
            object.add(name, readUniqueValue(input));
        }
        input.endObject();
        return object;
    }

    private static JsonElement readUniqueValue(JsonReader input) throws IOException {
        return switch (input.peek()) {
            case BEGIN_OBJECT -> readUniqueObject(input);
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                input.beginArray();
                while (input.hasNext()) {
                    array.add(readUniqueValue(input));
                }
                input.endArray();
                yield array;
            }
            default -> GSON.fromJson(input, JsonElement.class);
        };
    }

    public void save(Path file) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "quietframe-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(this) + "\n");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) ? number(json, key).intValueExact() : fallback;
    }

    private static float volume(JsonObject json, String key, float fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        BigDecimal value = number(json, key);
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(key + " must be between 0 and 1");
        }
        return value.floatValue();
    }

    private static BigDecimal number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        return value.getAsBigDecimal();
    }

    private static void requireRange(String key, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void validateVolume(String key, float value) {
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(key + " must be between 0 and 1");
        }
    }
}
