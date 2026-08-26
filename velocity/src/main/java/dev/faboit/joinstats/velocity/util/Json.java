package dev.faboit.joinstats.velocity.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

/** The single Gson instance the API server, webhooks and JSON columns all share. */
public final class Json {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private Json() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static String writePretty(Object value) {
        return PRETTY.toJson(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static JsonElement tree(String json) {
        return com.google.gson.JsonParser.parseString(json);
    }
}
