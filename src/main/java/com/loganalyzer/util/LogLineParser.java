package com.loganalyzer.util;

import com.loganalyzer.model.LogEntry;
import com.loganalyzer.model.LogEntry.Level;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless utility for parsing raw log lines into {@link LogEntry} objects.
 * Thread-safe — all state is immutable after construction.
 */
public class LogLineParser {

    private final Pattern logPattern;
    private final List<DateTimeFormatter> formatters;

    public LogLineParser(String patternRegex, List<String> timestampFormats) {
        this.logPattern = Pattern.compile(patternRegex);
        this.formatters = timestampFormats.stream()
            .map(DateTimeFormatter::ofPattern)
            .collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    /**
     * Parse a raw log line.
     *
     * @param rawLine    the full original line
     * @param lineNumber 1-based position in the source file
     * @param sourceFile name / path of the source file
     * @return parsed entry; level=UNKNOWN and null timestamp if parsing fails
     */
    public LogEntry parse(String rawLine, long lineNumber, String sourceFile) {
        if (rawLine == null || rawLine.isBlank()) {
            return malformed(rawLine, lineNumber, sourceFile);
        }

        Matcher m = logPattern.matcher(rawLine.trim());
        if (!m.matches()) {
            // Fallback: try to salvage at least the level
            return fallbackParse(rawLine, lineNumber, sourceFile);
        }

        LocalDateTime timestamp = parseTimestamp(m.group(1));
        Level level  = parseLevel(m.group(2));
        String thread = m.groupCount() >= 3 ? m.group(3).trim() : "unknown";
        String logger = m.groupCount() >= 4 ? m.group(4).trim() : "unknown";
        String msg    = m.groupCount() >= 5 ? m.group(5).trim() : rawLine;

        return new LogEntry(timestamp, level, thread, logger, msg, rawLine, lineNumber, sourceFile);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LogEntry fallbackParse(String rawLine, long lineNumber, String sourceFile) {
        Level level = Level.UNKNOWN;
        String upper = rawLine.toUpperCase();
        for (Level l : Level.values()) {
            if (l != Level.UNKNOWN && upper.contains(l.name())) {
                level = l;
                break;
            }
        }
        return new LogEntry(null, level, "unknown", "unknown", rawLine, rawLine, lineNumber, sourceFile);
    }

    private LogEntry malformed(String rawLine, long lineNumber, String sourceFile) {
        return new LogEntry(null, Level.UNKNOWN, "unknown", "unknown",
                            rawLine == null ? "" : rawLine,
                            rawLine == null ? "" : rawLine,
                            lineNumber, sourceFile);
    }

    private LocalDateTime parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (DateTimeFormatter fmt : formatters) {
            try {
                return LocalDateTime.parse(raw.trim(), fmt);
            } catch (DateTimeParseException ignored) { /* try next */ }
        }
        return null;
    }

    public static Level parseLevel(String raw) {
        if (raw == null) return Level.UNKNOWN;
        switch (raw.toUpperCase().trim()) {
            case "TRACE":   return Level.TRACE;
            case "DEBUG":   return Level.DEBUG;
            case "INFO":    return Level.INFO;
            case "WARN":
            case "WARNING": return Level.WARN;
            case "ERROR":
            case "SEVERE":  return Level.ERROR;
            case "FATAL":   return Level.FATAL;
            default:        return Level.UNKNOWN;
        }
    }
}
