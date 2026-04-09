package com.loganalyzer.report;

import com.loganalyzer.model.AnalysisResult;
import com.loganalyzer.model.LogEntry.Level;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates a human-readable analysis report to stdout and/or a file.
 */
public class ReportGenerator {

    private static final String SEP  = "═".repeat(70);
    private static final String SEP2 = "─".repeat(70);
    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Print report to stdout and write to {@code outputFile} (if non-null).
     */
    public void generate(AnalysisResult result,
                         String outputFile,
                         PerformanceReport perfReport) {

        String report = buildReport(result, perfReport);
        System.out.println(report);

        if (outputFile != null && !outputFile.isBlank()) {
            try (PrintWriter pw = new PrintWriter(
                    new BufferedWriter(new FileWriter(outputFile, StandardCharsets.UTF_8)))) {
                pw.println(report);
                System.out.println("\n✔  Report written to: " + outputFile);
            } catch (IOException e) {
                System.err.println("⚠  Could not write report: " + e.getMessage());
            }
        }
    }

    // ── Report building ───────────────────────────────────────────────────────

    private String buildReport(AnalysisResult result, PerformanceReport perf) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n").append(SEP).append("\n");
        sb.append("   MULTITHREADED LOG ANALYZER — ANALYSIS REPORT\n");
        sb.append(SEP).append("\n\n");

        // Overview
        sb.append("▶ OVERVIEW\n").append(SEP2).append("\n");
        sb.append(String.format("  Total Lines Read   : %,d%n",  result.getTotalLines()));
        sb.append(String.format("  Parsed Entries     : %,d%n",  result.getProcessedEntries()));
        sb.append(String.format("  Malformed Lines    : %,d%n",  result.getMalformedLines()));

        if (result.getEarliestTimestamp() != null)
            sb.append(String.format("  Earliest Timestamp : %s%n", result.getEarliestTimestamp().format(DT_FMT)));
        if (result.getLatestTimestamp() != null)
            sb.append(String.format("  Latest Timestamp   : %s%n", result.getLatestTimestamp().format(DT_FMT)));

        sb.append(String.format("  Processing Time    : %,d ms%n%n", result.getProcessingTimeMs()));

        // Level distribution
        sb.append("▶ LOG LEVEL DISTRIBUTION\n").append(SEP2).append("\n");
        Map<Level, Long> levels = result.getLevelCounts();
        long total = result.getProcessedEntries();
        for (Level lvl : new Level[]{Level.FATAL, Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE, Level.UNKNOWN}) {
            long count = levels.getOrDefault(lvl, 0L);
            if (count == 0) continue;
            double pct = total > 0 ? (count * 100.0 / total) : 0;
            sb.append(String.format("  %-8s : %,7d  (%5.1f%%)  %s%n",
                lvl, count, pct, bar(pct, 30)));
        }
        sb.append("\n");

        // Top error patterns
        sb.append("▶ TOP ERROR PATTERNS\n").append(SEP2).append("\n");
        List<Map.Entry<String, Long>> topErrors = result.getTopErrorPatterns(10);
        if (topErrors.isEmpty()) {
            sb.append("  (no errors detected)\n");
        } else {
            int rank = 1;
            for (Map.Entry<String, Long> e : topErrors) {
                sb.append(String.format("  %2d. %-40s  %,d occurrences%n", rank++, e.getKey(), e.getValue()));
            }
        }
        sb.append("\n");

        // Keyword hits
        Map<String, Long> kwHits = result.getKeywordHits();
        if (!kwHits.isEmpty()) {
            sb.append("▶ KEYWORD SEARCH RESULTS\n").append(SEP2).append("\n");
            kwHits.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(String.format("  %-25s : %,d hits%n", e.getKey(), e.getValue())));
            sb.append("\n");
        }

        // Hourly activity
        Map<String, Long> hourly = result.getHourlyBuckets();
        if (!hourly.isEmpty()) {
            sb.append("▶ HOURLY ACTIVITY\n").append(SEP2).append("\n");
            long maxCount = hourly.values().stream().mapToLong(Long::longValue).max().orElse(1);
            hourly.forEach((bucket, count) -> {
                double pct = count * 100.0 / maxCount;
                sb.append(String.format("  %s  %,6d  %s%n", bucket, count, bar(pct, 40)));
            });
            sb.append("\n");
        }

        // Per-file stats
        Map<String, Long> files = result.getFileCounts();
        if (files.size() > 1) {
            sb.append("▶ PER-FILE STATISTICS\n").append(SEP2).append("\n");
            files.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(String.format("  %-40s  %,d lines%n", e.getKey(), e.getValue())));
            sb.append("\n");
        }

        // Performance comparison
        if (perf != null) {
            sb.append("▶ PERFORMANCE COMPARISON\n").append(SEP2).append("\n");
            sb.append(String.format("  Single-threaded  : %,d ms%n",  perf.getSingleThreadedMs()));
            sb.append(String.format("  Multi-threaded   : %,d ms  (%d threads)%n",
                perf.getMultiThreadedMs(), perf.getThreadCount()));
            double speedup = perf.getSingleThreadedMs() > 0
                ? (double) perf.getSingleThreadedMs() / perf.getMultiThreadedMs() : 0;
            sb.append(String.format("  Speedup          : %.2fx%n%n", speedup));
        }

        sb.append(SEP).append("\n");
        return sb.toString();
    }

    /** Simple ASCII bar proportional to pct/100 * width */
    private String bar(double pct, int width) {
        int filled = (int) Math.round(pct / 100.0 * width);
        return "█".repeat(Math.max(0, filled)) + "░".repeat(Math.max(0, width - filled));
    }

    // ── Inner DTO ─────────────────────────────────────────────────────────────

    public static class PerformanceReport {
        private final long singleThreadedMs;
        private final long multiThreadedMs;
        private final int  threadCount;

        public PerformanceReport(long single, long multi, int threads) {
            this.singleThreadedMs = single;
            this.multiThreadedMs  = multi;
            this.threadCount      = threads;
        }

        public long getSingleThreadedMs() { return singleThreadedMs; }
        public long getMultiThreadedMs()  { return multiThreadedMs; }
        public int  getThreadCount()      { return threadCount; }
    }
}
