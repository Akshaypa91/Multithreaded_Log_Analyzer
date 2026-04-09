package com.loganalyzer.core;

import com.loganalyzer.analyzer.SingleThreadedAnalyzer;
import com.loganalyzer.config.AnalyzerConfig;
import com.loganalyzer.model.AnalysisResult;
import com.loganalyzer.model.LogChunk;
import com.loganalyzer.monitor.RealtimeLogMonitor;
import com.loganalyzer.processor.LogProcessor;
import com.loganalyzer.reader.LogFileReader;
import com.loganalyzer.report.ReportGenerator;
import com.loganalyzer.report.ReportGenerator.PerformanceReport;
import com.loganalyzer.util.LogLineParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Central orchestrator for the Multithreaded Log Analyzer.
 *
 * <h3>Architecture</h3>
 * <pre>
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │                    LogAnalyzerEngine                         │
 *  │                                                              │
 *  │  ┌────────────────────┐      ┌──────────────────────────┐   │
 *  │  │  Producer Pool     │      │  Consumer Pool           │   │
 *  │  │  LogFileReader × N │─────▶│  LogProcessor × M        │   │
 *  │  │  (one per file)    │      │  (configurable threads)  │   │
 *  │  └────────────────────┘      └──────────┬───────────────┘   │
 *  │           │                             │                    │
 *  │   BlockingQueue<LogChunk>         AnalysisResult             │
 *  │   (bounded, back-pressure)        (ConcurrentHashMap)        │
 *  └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Pattern: Producer-Consumer with poison-pill termination.
 */
public class LogAnalyzerEngine {

    private static final Logger LOG = Logger.getLogger(LogAnalyzerEngine.class.getName());

    private final AnalyzerConfig  config;
    private final LogLineParser   parser;
    private final ReportGenerator reporter;

    public LogAnalyzerEngine(AnalyzerConfig config) {
        this.config   = config;
        this.parser   = new LogLineParser(config.getLogPattern(), config.getTimestampFormats());
        this.reporter = new ReportGenerator();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Full analysis pipeline: optionally run single-threaded baseline,
     * then run multi-threaded analysis, then generate the report.
     */
    public AnalysisResult analyze(List<Path> files) throws InterruptedException {
        LOG.info("=== Log Analyzer Engine starting ===");
        LOG.info("Files : " + files.size());
        LOG.info("Config: " + config);

        PerformanceReport perfReport = null;

        // ── Baseline (single-threaded) ───────────────────────────────────────
        long singleMs = 0;
        if (config.isPerformanceComparison()) {
            LOG.info("\n--- Single-threaded baseline ---");
            SingleThreadedAnalyzer single = new SingleThreadedAnalyzer(parser, config.getKeywords());
            AnalysisResult baselineResult = single.analyze(files);
            singleMs = baselineResult.getProcessingTimeMs();
            LOG.info(String.format("Single-threaded: %,d ms", singleMs));
        }

        // ── Multi-threaded analysis ──────────────────────────────────────────
        LOG.info("\n--- Multi-threaded analysis (" + config.getThreadPoolSize() + " threads) ---");
        long multiStart = System.currentTimeMillis();
        AnalysisResult result = runMultiThreaded(files);
        long multiMs = System.currentTimeMillis() - multiStart;
        result.setProcessingTimeMs(multiMs);

        if (config.isPerformanceComparison()) {
            perfReport = new PerformanceReport(singleMs, multiMs, config.getThreadPoolSize());
        }

        // ── Real-time monitoring (optional) ──────────────────────────────────
        if (config.isRealtimeMonitoring() && !files.isEmpty()) {
            startRealtimeMonitor(files.get(0), result);
        }

        // ── Report ────────────────────────────────────────────────────────────
        reporter.generate(result, config.getOutputFile(), perfReport);

        return result;
    }

    // ── Multi-threaded pipeline ───────────────────────────────────────────────

    private AnalysisResult runMultiThreaded(List<Path> files) throws InterruptedException {

        int consumerCount = config.getThreadPoolSize();
        BlockingQueue<LogChunk> queue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        AnalysisResult result          = new AnalysisResult();
        CountDownLatch consumerLatch   = new CountDownLatch(consumerCount);
        AtomicInteger  chunkIdGen      = new AtomicInteger(0);

        // ── Thread pools ──────────────────────────────────────────────────────
        // Producers: one thread per file (bounded)
        ExecutorService producerPool = Executors.newFixedThreadPool(
            Math.min(files.size(), 4),
            r -> { Thread t = new Thread(r, "producer-" + r.hashCode()); t.setDaemon(true); return t; }
        );

        // Consumers
        ExecutorService consumerPool = Executors.newFixedThreadPool(
            consumerCount,
            r -> { Thread t = new Thread(r, "consumer-" + r.hashCode()); t.setDaemon(true); return t; }
        );

        // ── Submit consumers first ────────────────────────────────────────────
        for (int i = 0; i < consumerCount; i++) {
            consumerPool.submit(new LogProcessor(
                i, queue, result, parser, config.getKeywords(), consumerLatch));
        }

        // ── Submit producers ──────────────────────────────────────────────────
        List<Future<?>> producerFutures = new ArrayList<>();
        for (Path file : files) {
            producerFutures.add(producerPool.submit(
                new LogFileReader(file, queue, config.getChunkSizeLines(), consumerCount, chunkIdGen)));
        }

        // ── Wait for all producers to finish ──────────────────────────────────
        for (Future<?> f : producerFutures) {
            try { f.get(); } catch (ExecutionException e) {
                LOG.severe("Producer error: " + e.getCause().getMessage());
            }
        }

        // ── Send one poison pill per consumer ─────────────────────────────────
        LOG.info("All producers done — sending " + consumerCount + " poison pills.");
        for (int i = 0; i < consumerCount; i++) {
            queue.put(LogChunk.POISON_PILL);
        }

        // ── Wait for all consumers ────────────────────────────────────────────
        boolean finished = consumerLatch.await(10, TimeUnit.MINUTES);
        if (!finished) {
            LOG.warning("Consumers did not finish within timeout.");
        }

        // ── Shutdown ──────────────────────────────────────────────────────────
        producerPool.shutdownNow();
        consumerPool.shutdownNow();

        LOG.info(String.format("Multi-threaded done. %,d chunks processed.", chunkIdGen.get()));
        return result;
    }

    // ── Real-time monitor ─────────────────────────────────────────────────────

    private void startRealtimeMonitor(Path file, AnalysisResult result) {
        RealtimeLogMonitor monitor = new RealtimeLogMonitor(
            file, result, parser, config.getKeywords(), config.getMonitoringPollMs());

        Thread monitorThread = new Thread(monitor, "realtime-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();

        LOG.info("[Monitor] Real-time monitoring started for: " + file.getFileName());

        // Register shutdown hook to stop gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            monitor.stop();
            LOG.info("[Monitor] Shutdown hook: monitor stopped.");
        }));
    }
}
