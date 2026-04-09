package com.loganalyzer.model;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe accumulator for analysis results.
 * Uses atomic counters and concurrent collections so multiple
 * analyser threads can write without external synchronization.
 */
public class AnalysisResult {

    // ── Level counters ────────────────────────────────────────────────────────
    private final Map<LogEntry.Level, AtomicLong> levelCounts = new ConcurrentHashMap<>();

    // ── Error patterns: pattern → count ──────────────────────────────────────
    private final Map<String, AtomicLong> errorPatterns = new ConcurrentHashMap<>();

    // ── Keyword hits: keyword → count ────────────────────────────────────────
    private final Map<String, AtomicLong> keywordHits = new ConcurrentHashMap<>();

    // ── Hourly buckets: "yyyy-MM-dd HH" → count ──────────────────────────────
    private final Map<String, AtomicLong> hourlyBuckets = new ConcurrentHashMap<>();

    // ── Source file stats: file → count ──────────────────────────────────────
    private final Map<String, AtomicLong> fileCounts = new ConcurrentHashMap<>();

    // ── Totals ────────────────────────────────────────────────────────────────
    private final AtomicLong totalLines       = new AtomicLong(0);
    private final AtomicLong malformedLines   = new AtomicLong(0);
    private final AtomicLong processedEntries = new AtomicLong(0);

    // ── Time range ────────────────────────────────────────────────────────────
    private volatile LocalDateTime earliestTimestamp;
    private volatile LocalDateTime latestTimestamp;

    // ── Performance ───────────────────────────────────────────────────────────
    private volatile long processingTimeMs;

    public AnalysisResult() {
        for (LogEntry.Level lvl : LogEntry.Level.values()) {
            levelCounts.put(lvl, new AtomicLong(0));
        }
    }

    // ── Mutation helpers ──────────────────────────────────────────────────────

    public void incrementLevel(LogEntry.Level level) {
        levelCounts.get(level).incrementAndGet();
    }

    public void incrementErrorPattern(String pattern) {
        errorPatterns.computeIfAbsent(pattern, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void incrementKeyword(String keyword) {
        keywordHits.computeIfAbsent(keyword, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void incrementHourlyBucket(String bucket) {
        hourlyBuckets.computeIfAbsent(bucket, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void incrementFileCount(String file) {
        fileCounts.computeIfAbsent(file, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void incrementTotalLines()     { totalLines.incrementAndGet(); }
    public void incrementMalformed()      { malformedLines.incrementAndGet(); }
    public void incrementProcessed()      { processedEntries.incrementAndGet(); }

    public synchronized void updateTimestampRange(LocalDateTime ts) {
        if (ts == null) return;
        if (earliestTimestamp == null || ts.isBefore(earliestTimestamp))  earliestTimestamp = ts;
        if (latestTimestamp   == null || ts.isAfter(latestTimestamp))     latestTimestamp   = ts;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Map<LogEntry.Level, Long> getLevelCounts() {
        Map<LogEntry.Level, Long> snapshot = new EnumMap<>(LogEntry.Level.class);
        levelCounts.forEach((k, v) -> snapshot.put(k, v.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Long> getErrorPatterns() {
        Map<String, Long> snapshot = new HashMap<>();
        errorPatterns.forEach((k, v) -> snapshot.put(k, v.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Long> getKeywordHits() {
        Map<String, Long> snapshot = new HashMap<>();
        keywordHits.forEach((k, v) -> snapshot.put(k, v.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Long> getHourlyBuckets() {
        Map<String, Long> snapshot = new TreeMap<>();
        hourlyBuckets.forEach((k, v) -> snapshot.put(k, v.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Long> getFileCounts() {
        Map<String, Long> snapshot = new HashMap<>();
        fileCounts.forEach((k, v) -> snapshot.put(k, v.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public long getTotalLines()       { return totalLines.get(); }
    public long getMalformedLines()   { return malformedLines.get(); }
    public long getProcessedEntries() { return processedEntries.get(); }
    public LocalDateTime getEarliestTimestamp() { return earliestTimestamp; }
    public LocalDateTime getLatestTimestamp()   { return latestTimestamp; }
    public long getProcessingTimeMs()           { return processingTimeMs; }

    public void setProcessingTimeMs(long ms)    { this.processingTimeMs = ms; }

    /** Return top-N error patterns sorted by frequency. */
    public List<Map.Entry<String, Long>> getTopErrorPatterns(int n) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(getErrorPatterns().entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(n, list.size()));
    }
}
