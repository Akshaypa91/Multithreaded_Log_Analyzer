package com.loganalyzer.monitor;

import com.loganalyzer.model.AnalysisResult;
import com.loganalyzer.model.LogEntry;
import com.loganalyzer.util.LogLineParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Monitors a log file in real-time (tail -f style) using Java NIO WatchService.
 * Continuously appends new lines into the shared {@link AnalysisResult}.
 *
 * <p>Call {@link #stop()} to terminate monitoring.
 */
public class RealtimeLogMonitor implements Runnable {

    private static final Logger LOG = Logger.getLogger(RealtimeLogMonitor.class.getName());

    private final Path           filePath;
    private final AnalysisResult result;
    private final LogLineParser  parser;
    private final List<String>   keywords;
    private final long           pollIntervalMs;
    private final AtomicBoolean  running = new AtomicBoolean(true);

    public RealtimeLogMonitor(Path filePath,
                              AnalysisResult result,
                              LogLineParser parser,
                              List<String> keywords,
                              long pollIntervalMs) {
        this.filePath       = filePath;
        this.result         = result;
        this.parser         = parser;
        this.keywords       = keywords;
        this.pollIntervalMs = pollIntervalMs;
    }

    public void stop() {
        running.set(false);
        LOG.info("[Monitor] Stop requested for " + filePath.getFileName());
    }

    @Override
    public void run() {
        LOG.info("[Monitor] Watching: " + filePath);
        String fileName = filePath.getFileName().toString();

        try {
            // Seek to end of file first (tail behaviour)
            long filePointer = filePath.toFile().length();
            long lineNumber  = 0;

            while (running.get()) {
                long currentLength = filePath.toFile().length();

                if (currentLength > filePointer) {
                    try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
                        raf.seek(filePointer);
                        String line;
                        while ((line = raf.readLine()) != null) {
                            // RandomAccessFile.readLine() returns ISO-8859-1 — re-encode
                            String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1),
                                                        StandardCharsets.UTF_8);
                            lineNumber++;
                            processLine(decoded, lineNumber, fileName);
                        }
                        filePointer = raf.getFilePointer();
                    }
                } else if (currentLength < filePointer) {
                    // File was rotated/truncated
                    LOG.info("[Monitor] File rotated — resetting pointer.");
                    filePointer = 0;
                    lineNumber  = 0;
                }

                Thread.sleep(pollIntervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.severe("[Monitor] IO error: " + e.getMessage());
        }

        LOG.info("[Monitor] Stopped watching " + filePath.getFileName());
    }

    private void processLine(String rawLine, long lineNumber, String sourceFile) {
        result.incrementTotalLines();
        LogEntry entry = parser.parse(rawLine, lineNumber, sourceFile);
        result.incrementLevel(entry.getLevel());
        result.incrementProcessed();
        result.incrementFileCount(sourceFile);
        result.updateTimestampRange(entry.getTimestamp());

        if (entry.getLevel() == LogEntry.Level.ERROR || entry.getLevel() == LogEntry.Level.FATAL) {
            LOG.warning("[Monitor][LIVE] " + rawLine);
        }

        for (String kw : keywords) {
            if (rawLine.toLowerCase().contains(kw.toLowerCase())) {
                result.incrementKeyword(kw);
            }
        }
    }
}
