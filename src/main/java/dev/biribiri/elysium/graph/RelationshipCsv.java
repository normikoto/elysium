package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.graph.internal.RelationshipFileRepository.ExportedAccount;
import dev.biribiri.elysium.graph.internal.RelationshipFileRepository.Row;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads and writes files of accounts in the columns Mastodon's exports have, so that files move both ways. Mastodon writes
 * a header for follows and mutes but not for blocks, and older exports and files made by hand are a column of addresses,
 * so a header is optional when reading, and only the address is needed.
 */
final class RelationshipCsv {

    /**
     * As many as Mastodon imports at once.
     */
    static final int MAX_ROWS = 20_000;

    private static final String ADDRESS = "Account address";
    private static final String SHOW_BOOSTS = "Show boosts";
    private static final String NOTIFY = "Notify on new posts";
    private static final String LANGUAGES = "Languages";
    private static final String HIDE_NOTIFICATIONS = "Hide notifications";

    /**
     * Ruby's CSV, which Mastodon uses, ends lines with a newline only.
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setRecordSeparator('\n')
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get();

    /**
     * What WebFinger and other servers' exports allow in an address, which is looser than this server's own usernames.
     */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("([^\\s@/:?#]+)(?:@([A-Za-z0-9.-]+(?::[0-9]{1,5})?))?");

    private RelationshipCsv() {
    }

    /**
     * Reads the accounts in a file, leaving out any listed twice.
     *
     * @throws InvalidRelationshipFileException if it isn't CSV, has an address that isn't one, or lists none or too many
     */
    static List<Row> read(RelationshipKind kind, String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<CSVRecord> records;
        try (var parser = CSVParser.parse(new StringReader(text), FORMAT)) {
            records = parser.getRecords();
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            throw new InvalidRelationshipFileException("The file isn't a CSV file");
        }
        Map<String, Integer> columns = Map.of();
        if (!records.isEmpty() && records.getFirst().get(0).equalsIgnoreCase(ADDRESS)) {
            columns = columns(records.getFirst());
            records = records.subList(1, records.size());
        }
        var rows = new ArrayList<Row>();
        var seen = new HashSet<String>();
        for (var record : records) {
            var address = record.get(0);
            if (address.isEmpty()) {
                continue;
            }
            var matcher = ADDRESS_PATTERN.matcher(stripPrefix(address));
            if (!matcher.matches()) {
                throw new InvalidRelationshipFileException("\"%s\" in row %d isn't an address like alice@example.com"
                        .formatted(address, record.getRecordNumber()));
            }
            var username = matcher.group(1);
            var domain = matcher.group(2) == null ? null : matcher.group(2).toLowerCase(Locale.ROOT);
            if (!seen.add(username.toLowerCase(Locale.ROOT) + "@" + domain)) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new InvalidRelationshipFileException("A file can't list more than %,d accounts".formatted(MAX_ROWS));
            }
            rows.add(switch (kind) {
                case FOLLOWS -> new Row(rows.size(), username, domain, bool(record, columns, SHOW_BOOSTS),
                        bool(record, columns, NOTIFY), languages(record, columns), null);
                case BLOCKS -> new Row(rows.size(), username, domain, null, null, null, null);
                case MUTES -> new Row(rows.size(), username, domain, null, null, null,
                        bool(record, columns, HIDE_NOTIFICATIONS));
            });
        }
        if (rows.isEmpty()) {
            throw new InvalidRelationshipFileException("The file doesn't list any accounts");
        }
        return rows;
    }

    static void write(RelationshipKind kind, List<ExportedAccount> accounts, OutputStream out) throws IOException {
        var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        var printer = new CSVPrinter(writer, FORMAT);
        switch (kind) {
            case FOLLOWS -> printer.printRecord(ADDRESS, SHOW_BOOSTS, NOTIFY, LANGUAGES);
            case MUTES -> printer.printRecord(ADDRESS, HIDE_NOTIFICATIONS);
            case BLOCKS -> {
            }
        }
        for (var account : accounts) {
            var address = account.username() + "@" + account.domain();
            switch (kind) {
                case FOLLOWS -> printer.printRecord(address, account.showReblogs(), account.notifies(),
                        account.languages() == null ? "" : String.join(", ", account.languages()));
                case MUTES -> printer.printRecord(address, account.hideNotifications());
                case BLOCKS -> printer.printRecord(address);
            }
        }
        printer.flush();
    }

    private static Map<String, Integer> columns(CSVRecord header) {
        var columns = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
        for (var i = 0; i < header.size(); i++) {
            columns.putIfAbsent(header.get(i), i);
        }
        return columns;
    }

    private static String stripPrefix(String address) {
        if (address.regionMatches(true, 0, "acct:", 0, 5)) {
            address = address.substring(5);
        }
        return address.startsWith("@") ? address.substring(1) : address;
    }

    /**
     * @return null when the file has no such column or leaves it empty, so the default stays
     */
    private static Boolean bool(CSVRecord record, Map<String, Integer> columns, String column) {
        var value = cell(record, columns, column);
        if (value == null || value.isEmpty()) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidRelationshipFileException("\"%s\" in the %s column in row %d isn't true or false"
                    .formatted(value, column, record.getRecordNumber()));
        };
    }

    /**
     * Mastodon separates them with commas, in one quoted column. An empty column means every language.
     */
    private static List<String> languages(CSVRecord record, Map<String, Integer> columns) {
        var value = cell(record, columns, LANGUAGES);
        if (value == null) {
            return null;
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(language -> !language.isEmpty()).toList();
    }

    private static String cell(CSVRecord record, Map<String, Integer> columns, String column) {
        var index = columns.get(column);
        return index == null || index >= record.size() ? null : record.get(index);
    }
}
