package com.loganalyzer.reader;

import com.loganalyzer.model.LogChunk;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Producer: streams a log file in chunks and places them on a {@link BlockingQueue}.
 *
 * <p>Implements {@link Runnable} so it can be submitted to an {@link java.util.concurrent.ExecutorService}.
 * After exhausting the file it places one {@link LogChunk#POISON_PILL} per consumer thread.
 */
public class LogFileReader implements Runnable {

    private static final Logger LOG = Logger.getLogger(LogFileReader.class.getName());

    private final Path           filePath;
    private final BlockingQueue<LogChunk> queue;
    private final int            chunkSizeLines;
    private final int            consumerCount;      // how many poison pills to emit
    private final AtomicInteger  chunkIdGenerator;

    public LogFileReader(Path filePath,
                         BlockingQueue<LogChunk> queue,
                         int chunkSizeLines,
                         int consumerCount,
                         AtomicInteger chunkIdGenerator) {
        this.filePath         = filePath;
        this.queue            = queue;
        this.chunkSizeLines   = chunkSizeLines;
        this.consumerCount    = consumerCount;
        this.chunkIdGenerator = chunkIdGenerator;
    }

    @Override
    public void run() {
        String fileName = filePath.getFileName().toString();
        LOG.info("[Reader] Starting: " + fileName);

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            List<String> buffer = new ArrayList<>(chunkSizeLines);
            String line;
            long lineNumber = 0;
            long chunkStart = 1;

            while ((line = br.readLine()) != null) {
                lineNumber++;
                buffer.add(line);

                if (buffer.size() >= chunkSizeLines) {
                    enqueue(new LogChunk(new ArrayList<>(buffer), fileName, chunkStart,
                                        chunkIdGenerator.incrementAndGet()));
                    buffer.clear();
                    chunkStart = lineNumber + 1;
                }
            }

            // Flush remaining lines
            if (!buffer.isEmpty()) {
                enqueue(new LogChunk(new ArrayList<>(buffer), fileName, chunkStart,
                                     chunkIdGenerator.incrementAndGet()));
            }

            LOG.info(String.format("[Reader] Finished %s — %d lines, %d chunks",
                fileName, lineNumber, chunkIdGenerator.get()));

        } catch (IOException e) {
            LOG.severe("[Reader] Error reading " + fileName + ": " + e.getMessage());
        } finally {
            // One producer puts one poison pill; LogAnalyzerEngine coordinates extras
        }
    }

    private void enqueue(LogChunk chunk) {
        try {
            queue.put(chunk);   // blocks if queue is full — natural back-pressure
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("[Reader] Interrupted while enqueuing chunk " + chunk.getChunkId());
        }
    }
}
