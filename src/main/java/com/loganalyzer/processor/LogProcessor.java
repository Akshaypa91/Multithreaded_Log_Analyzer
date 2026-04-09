package com.loganalyzer.processor;

import com.loganalyzer.model.AnalysisResult;
import com.loganalyzer.model.LogChunk;
import com.loganalyzer.model.LogEntry;
import com.loganalyzer.util.LogLineParser;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Consumer: pulls {@link LogChunk}s from the queue, parses each line,
 * and merges statistics into the shared {@link AnalysisResult}.
 *
 * <p>Runs until it sees a {@link LogChunk#POISON_PILL}, then decrements the latch.
 */
public class LogProcessor implements Runnable {

    private static final Logger LOG = Logger.getLogger(LogProcessor.class.getName());

    // Regex patterns for common error categories
    private static final Pattern NPE_PATTERN     = Pattern.compile("NullPointerException", Pattern.CASE_INSENSITIVE);
    private static final Pattern OOM_PATTERN     = Pattern.compile("OutOfMemoryError",     Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMEOUT_PATTERN = Pattern.compile("timeout|timed.?out",   Pattern.CASE_INSENSITIVE);
    private static final Pattern CONN_PATTERN    = Pattern.compile("connection.?(refused|reset|closed)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_PATTERN     = Pattern.compile("SQLException|deadlock", Pattern.CASE_INSENSITIVE);

    private final int                    processorId;
    private final BlockingQueue<LogChunk> queue;
    private final AnalysisResult         result;
    private final LogLineParser          parser;
    private final List<String>           keywords;
    private final CountDownLatch         completionLatch;

    public LogProcessor(int processorId,
                        BlockingQueue<LogChunk> queue,
                        AnalysisResult result,
                        LogLineParser parser,
                        List<String> keywords,
                        CountDownLatch completionLatch) {
        this.processorId     = processorId;
        this.queue           = queue;
        this.result          = result;
        this.parser          = parser;
        this.keywords        = keywords;
        this.completionLatch = completionLatch;
    }

    @Override
    public void run() {
        LOG.fine("[Processor-" + processorId + "] Started");
        long localProcessed = 0;

        try {
            while (true) {
                LogChunk chunk = queue.take();   // blocks when queue is empty

                if (chunk.isPoisonPill()) {
                    LOG.fine("[Processor-" + processorId + "] Received poison pill, stopping.");
                    break;
                }

                processChunk(chunk);
                localProcessed += chunk.getLines().size();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("[Processor-" + processorId + "] Interrupted.");
        } finally {
            LOG.info(String.format("[Processor-%d] Done. Processed ~%d lines.", processorId, localProcessed));
            completionLatch.countDown();
        }
    }

    // ── Chunk processing ──────────────────────────────────────────────────────

    private void processChunk(LogChunk chunk) {
        List<String> lines = chunk.getLines();
        long lineNum = chunk.getStartLine();

        for (String rawLine : lines) {
            result.incrementTotalLines();

            LogEntry entry = parser.parse(rawLine, lineNum++, chunk.getSourceFile());

            if (entry.getLevel() == LogEntry.Level.UNKNOWN && entry.getTimestamp() == null) {
                result.incrementMalformed();
            }

            result.incrementLevel(entry.getLevel());
            result.incrementProcessed();
            result.incrementFileCount(chunk.getSourceFile());
            result.updateTimestampRange(entry.getTimestamp());

            // Hourly bucket
            if (entry.getTimestamp() != null) {
                String bucket = entry.getTimestamp()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH"));
                result.incrementHourlyBucket(bucket);
            }

            // Error pattern detection (only for ERROR/FATAL)
            if (entry.getLevel() == LogEntry.Level.ERROR || entry.getLevel() == LogEntry.Level.FATAL) {
                detectErrorPatterns(entry.getMessage());
            }

            // Keyword search
            for (String kw : keywords) {
                if (rawLine.toLowerCase().contains(kw.toLowerCase())) {
                    result.incrementKeyword(kw);
                }
            }
        }
    }

    private void detectErrorPatterns(String message) {
        if (NPE_PATTERN.matcher(message).find())     result.incrementErrorPattern("NullPointerException");
        if (OOM_PATTERN.matcher(message).find())     result.incrementErrorPattern("OutOfMemoryError");
        if (TIMEOUT_PATTERN.matcher(message).find()) result.incrementErrorPattern("Timeout/TimedOut");
        if (CONN_PATTERN.matcher(message).find())    result.incrementErrorPattern("Connection Failure");
        if (SQL_PATTERN.matcher(message).find())     result.incrementErrorPattern("SQL/Deadlock");

        // Generic: first word that ends with "Exception" or "Error"
        java.util.regex.Matcher m = Pattern.compile("\\b(\\w*(Exception|Error))\\b").matcher(message);
        if (m.find()) {
            result.incrementErrorPattern(m.group(1));
        }
    }
}
