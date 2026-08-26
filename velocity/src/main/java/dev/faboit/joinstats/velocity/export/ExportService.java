package dev.faboit.joinstats.velocity.export;

import dev.faboit.joinstats.velocity.storage.Database;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dumps tables to CSV or newline-delimited JSON.
 *
 * <p>Rows are streamed straight from the {@link ResultSet} to the file. A network's session table
 * runs to millions of rows, and materialising one into a list before writing it would turn an
 * export into an out-of-memory kill.
 */
public final class ExportService {

    /**
     * What can be exported, and the query behind each.
     *
     * <p>A fixed set rather than a caller-supplied query: the command that reaches this is
     * available to staff, and "export" that accepts arbitrary SQL is a way to read every table
     * regardless of what the permission nodes say.
     */
    private static final Map<String, String> EXPORTS = Map.of(
            "players", "SELECT * FROM js_players ORDER BY last_seen DESC",
            "sessions", "SELECT * FROM js_sessions WHERE started_at >= ? ORDER BY started_at DESC",
            "addresses", "SELECT * FROM js_addresses ORDER BY last_seen DESC",
            "events", "SELECT * FROM js_events WHERE at >= ? ORDER BY at DESC",
            "chat", "SELECT * FROM js_chat WHERE at >= ? ORDER BY at DESC",
            "commands", "SELECT * FROM js_commands WHERE at >= ? ORDER BY at DESC",
            "population", "SELECT * FROM js_population WHERE at >= ? ORDER BY at",
            "alerts", "SELECT * FROM js_alerts WHERE at >= ? ORDER BY at DESC",
            "placeholders", "SELECT * FROM js_placeholders ORDER BY uuid, placeholder",
            "servers", "SELECT * FROM js_player_servers ORDER BY playtime DESC");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final Database database;
    private final Path exportDirectory;

    public ExportService(Database database, Path dataDirectory) {
        this.database = database;
        this.exportDirectory = dataDirectory.resolve("exports");
    }

    /** The identifiers {@link #export} accepts. */
    public static List<String> targets() {
        return EXPORTS.keySet().stream().sorted().toList();
    }

    public static boolean isTarget(String name) {
        return EXPORTS.containsKey(name);
    }

    /**
     * Writes one table to a timestamped file.
     *
     * @param what   an entry from {@link #targets()}
     * @param format {@code csv} or {@code json}
     * @param since  epoch millis; ignored by the exports that have no time column
     */
    public CompletableFuture<Result> export(String what, String format, long since) {
        String sql = EXPORTS.get(what);
        if (sql == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Nothing called '" + what + "' can be exported"));
        }
        boolean json = "json".equalsIgnoreCase(format);
        String extension = json ? ".jsonl" : ".csv";
        Path target = exportDirectory.resolve(
                what + "-" + LocalDateTime.now().format(STAMP) + extension);

        return database.query(connection -> {
            long rows = 0;
            try {
                Files.createDirectories(exportDirectory);
            } catch (IOException e) {
                throw new Database.StorageException("Could not create " + exportDirectory, e);
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (sql.contains("?")) {
                    Database.bind(statement, since);
                }
                try (ResultSet results = statement.executeQuery();
                     BufferedWriter writer = Files.newBufferedWriter(target,
                             StandardCharsets.UTF_8)) {
                    ResultSetMetaData meta = results.getMetaData();
                    int columns = meta.getColumnCount();

                    if (!json) {
                        writeCsvHeader(writer, meta, columns);
                    }
                    while (results.next()) {
                        if (json) {
                            writeJsonRow(writer, results, meta, columns);
                        } else {
                            writeCsvRow(writer, results, columns);
                        }
                        rows++;
                    }
                }
            } catch (IOException e) {
                throw new Database.StorageException("Could not write " + target, e);
            }
            return new Result(target, rows);
        });
    }

    private static void writeCsvHeader(BufferedWriter writer, ResultSetMetaData meta, int columns)
            throws IOException, java.sql.SQLException {
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                writer.write(',');
            }
            writer.write(csvEscape(meta.getColumnLabel(i)));
        }
        writer.newLine();
    }

    private static void writeCsvRow(BufferedWriter writer, ResultSet results, int columns)
            throws IOException, java.sql.SQLException {
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                writer.write(',');
            }
            Object value = results.getObject(i);
            writer.write(value == null ? "" : csvEscape(String.valueOf(value)));
        }
        writer.newLine();
    }

    private static void writeJsonRow(BufferedWriter writer, ResultSet results,
                                     ResultSetMetaData meta, int columns)
            throws IOException, java.sql.SQLException {
        com.google.gson.JsonObject row = new com.google.gson.JsonObject();
        for (int i = 1; i <= columns; i++) {
            String label = meta.getColumnLabel(i);
            Object value = results.getObject(i);
            if (value == null) {
                row.add(label, com.google.gson.JsonNull.INSTANCE);
            } else if (value instanceof Number number) {
                row.addProperty(label, number);
            } else {
                row.addProperty(label, String.valueOf(value));
            }
        }
        writer.write(dev.faboit.joinstats.velocity.util.Json.write(row));
        writer.newLine();
    }

    /**
     * Quotes a CSV field.
     *
     * <p>A leading {@code =}, {@code +}, {@code -} or {@code @} is prefixed with an apostrophe.
     * Player-supplied text ends up in these files, and a spreadsheet opening one would otherwise
     * treat a username beginning with {@code =} as a formula to execute.
     */
    private static String csvEscape(String value) {
        String escaped = value;
        if (!escaped.isEmpty() && "=+-@\t\r".indexOf(escaped.charAt(0)) >= 0) {
            escaped = "'" + escaped;
        }
        if (escaped.indexOf(',') >= 0 || escaped.indexOf('"') >= 0
                || escaped.indexOf('\n') >= 0 || escaped.indexOf('\r') >= 0) {
            escaped = '"' + escaped.replace("\"", "\"\"") + '"';
        }
        return escaped;
    }

    /** Where an export landed, and how much of it there was. */
    public record Result(Path file, long rows) {
    }
}
