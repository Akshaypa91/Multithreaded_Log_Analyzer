package com.loganalyzer.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable model representing a single parsed log entry.
 * Thread-safe by design — all fields are final.
 */
public final class LogEntry implements Comparable<LogEntry> {

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN
    }

    private final LocalDateTime timestamp;
    private final Level level;
    private final String thread;
    private final String logger;
    private final String message;
    private final String rawLine;
    private final long lineNumber;
    private final String sourceFile;

    public LogEntry(LocalDateTime timestamp, Level level, String thread,
                    String logger, String message, String rawLine,
                    long lineNumber, String sourceFile) {
        this.timestamp  = timestamp;
        this.level      = level;
        this.thread     = thread;
        this.logger     = logger;
        this.message    = message;
        this.rawLine    = rawLine;
        this.lineNumber = lineNumber;
        this.sourceFile = sourceFile;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public LocalDateTime getTimestamp() { return timestamp; }
    public Level         getLevel()     { return level; }
    public String        getThread()    { return thread; }
    public String        getLogger()    { return logger; }
    public String        getMessage()   { return message; }
    public String        getRawLine()   { return rawLine; }
    public long          getLineNumber(){ return lineNumber; }
    public String        getSourceFile(){ return sourceFile; }

    /** Natural order = chronological */
    @Override
    public int compareTo(LogEntry other) {
        if (this.timestamp == null && other.timestamp == null) return 0;
        if (this.timestamp == null) return 1;
        if (other.timestamp == null) return -1;
        return this.timestamp.compareTo(other.timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogEntry)) return false;
        LogEntry e = (LogEntry) o;
        return lineNumber == e.lineNumber && Objects.equals(sourceFile, e.sourceFile);
    }

    @Override
    public int hashCode() { return Objects.hash(sourceFile, lineNumber); }

    @Override
    public String toString() {
        return String.format("[%s] %s %s - %s", level, timestamp, logger, message);
    }
}
