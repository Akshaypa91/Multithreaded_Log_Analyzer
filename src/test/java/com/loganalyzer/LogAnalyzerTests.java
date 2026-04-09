package com.loganalyzer;

import com.loganalyzer.config.AnalyzerConfig;
import com.loganalyzer.model.AnalysisResult;
import com.loganalyzer.model.LogEntry;
import com.loganalyzer.model.LogEntry.Level;
import com.loganalyzer.util.LogLineParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Log Analyzer core components.
 * Run with: mvn test  (or any JUnit 5 runner)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogAnalyzerTests {

    private static LogLineParser parser;
    private static AnalyzerConfig config;

    @BeforeAll
    static void setup() {
        config = AnalyzerConfig.defaults();
        parser = new LogLineParser(config.getLogPattern(), config.getTimestampFormats());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LogLineParser tests
    // ══════════════════════════════════════════════════════════════════════════

    @Test @Order(1)
    @DisplayName("Parse a well-formed INFO log line")
    void testParseWellFormedInfoLine() {
        String line = "2024-06-01 08:15:32.123 INFO  [main] com.example.App - Application started";
        LogEntry e = parser.parse(line, 1, "test.log");

        assertEquals(Level.INFO, e.getLevel());
        assertEquals("main", e.getThread());
        assertEquals("com.example.App", e.getLogger());
        assertTrue(e.getMessage().contains("Application started"));
        assertNotNull(e.getTimestamp());
        assertEquals(LocalDateTime.of(2024, 6, 1, 8, 15, 32, 123_000_000), e.getTimestamp());
    }

    @Test @Order(2)
    @DisplayName("Parse an ERROR log line")
    void testParseErrorLine() {
        String line = "2024-06-01 09:00:01.000 ERROR [http-nio-8080-exec-1] com.example.Svc - NullPointerException in handler";
        LogEntry e = parser.parse(line, 2, "test.log");
        assertEquals(Level.ERROR, e.getLevel());
        assertTrue(e.getMessage().contains("NullPointerException"));
    }

    @Test @Order(3)
    @DisplayName("Fallback parsing for malformed line still extracts level")
    void testFallbackParseMalformedLine() {
        String line = "Something went wrong ERROR in the system";
        LogEntry e = parser.parse(line, 3, "test.log");
        // Fallback should detect ERROR keyword
        assertEquals(Level.ERROR, e.getLevel());
        assertNull(e.getTimestamp());
    }

    @Test @Order(4)
    @DisplayName("Null line returns UNKNOWN level entry")
    void testNullLineReturnsUnknown() {
        LogEntry e = parser.parse(null, 0, "test.log");
        assertEquals(Level.UNKNOWN, e.getLevel());
        assertNull(e.getTimestamp());
    }

    @Test @Order(5)
    @DisplayName("Blank line returns UNKNOWN level entry")
    void testBlankLineReturnsUnknown() {
        LogEntry e = parser.parse("   ", 0, "test.log");
        assertEquals(Level.UNKNOWN, e.getLevel());
    }

    @ParameterizedTest(name = "Level string \"{0}\" → {1}")
    @Order(6)
    @CsvSource({
        "INFO,    INFO",
        "WARN,    WARN",
        "WARNING, WARN",
        "ERROR,   ERROR",
        "SEVERE,  ERROR",
        "DEBUG,   DEBUG",
        "TRACE,   TRACE",
        "FATAL,   FATAL",
        "GARBAGE, UNKNOWN"
    })
    @DisplayName("parseLevel handles all variants")
    void testParseLevel(String input, String expected) {
        assertEquals(Level.valueOf(expected.trim()),
                     LogLineParser.parseLevel(input.trim()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AnalysisResult concurrency test
    // ══════════════════════════════════════════════════════════════════════════

    @Test @Order(10)
    @DisplayName("AnalysisResult is thread-safe under concurrent writes")
    void testAnalysisResultThreadSafety() throws InterruptedException {
        AnalysisResult result = new AnalysisResult();
        int threads = 16;
        int perThread = 1000;

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    result.incrementLevel(Level.INFO);
                    result.incrementLevel(Level.ERROR);
                    result.incrementTotalLines();
                    result.incrementErrorPattern("NullPointerException");
                }
            });
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        long expectedTotal = (long) threads * perThread;
        assertEquals(expectedTotal, result.getLevelCounts().get(Level.INFO));
        assertEquals(expectedTotal, result.getLevelCounts().get(Level.ERROR));
        assertEquals(expectedTotal * 2, result.getTotalLines());
        assertEquals(expectedTotal, result.getErrorPatterns().get("NullPointerException"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AnalyzerConfig JSON loading
    // ══════════════════════════════════════════════════════════════════════════

    @Test @Order(20)
    @DisplayName("AnalyzerConfig loads from JSON correctly")
    void testConfigFromJson() throws Exception {
        String json = """
            {
              "threadPoolSize": 12,
              "queueCapacity": 500,
              "chunkSizeLines": 250,
              "keywords": ["timeout", "OOM"],
              "outputFile": "custom_report.txt",
              "realtimeMonitoring": true,
              "performanceComparison": false,
              "topErrorPatterns": 5
            }
            """;
        Path tmp = Files.createTempFile("test_config", ".json");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);

        AnalyzerConfig cfg = AnalyzerConfig.fromJson(tmp.toString());

        assertEquals(12, cfg.getThreadPoolSize());
        assertEquals(500, cfg.getQueueCapacity());
        assertEquals(250, cfg.getChunkSizeLines());
        assertEquals(List.of("timeout", "OOM"), cfg.getKeywords());
        assertEquals("custom_report.txt", cfg.getOutputFile());
        assertTrue(cfg.isRealtimeMonitoring());
        assertFalse(cfg.isPerformanceComparison());
        assertEquals(5, cfg.getTopErrorPatterns());

        Files.deleteIfExists(tmp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // End-to-end integration (in-memory file)
    // ══════════════════════════════════════════════════════════════════════════

    @Test @Order(30)
    @DisplayName("Full pipeline: produce + consume returns correct level counts")
    void testFullPipelineSmall() throws Exception {
        // Write a tiny log file
        Path tmp = Files.createTempFile("small_test", ".log");
        try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 100; i++) {
                bw.write("2024-06-01 10:00:" + String.format("%02d", i % 60) + ".000 INFO  [main] com.App - msg " + i + "\n");
            }
            for (int i = 0; i < 20; i++) {
                bw.write("2024-06-01 10:00:" + String.format("%02d", i % 60) + ".000 ERROR [main] com.App - NullPointerException occurred\n");
            }
            for (int i = 0; i < 5; i++) {
                bw.write("2024-06-01 10:00:" + String.format("%02d", i % 60) + ".000 WARN  [main] com.App - warning " + i + "\n");
            }
        }

        AnalyzerConfig cfg = AnalyzerConfig.defaults();
        cfg.setPerformanceComparison(false);
        cfg.setChunkSizeLines(50);
        cfg.setThreadPoolSize(2);

        com.loganalyzer.core.LogAnalyzerEngine engine = new com.loganalyzer.core.LogAnalyzerEngine(cfg);
        AnalysisResult result = engine.analyze(List.of(tmp));

        assertEquals(125, result.getTotalLines());
        assertEquals(100, result.getLevelCounts().get(Level.INFO));
        assertEquals(20,  result.getLevelCounts().get(Level.ERROR));
        assertEquals(5,   result.getLevelCounts().get(Level.WARN));
        assertTrue(result.getErrorPatterns().containsKey("NullPointerException"));

        Files.deleteIfExists(tmp);
    }
}
