package com.loganalyzer;

import com.loganalyzer.config.AnalyzerConfig;
import com.loganalyzer.core.LogAnalyzerEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.*;

/**
 * Entry point for the Multithreaded Log Analyzer.
 *
 * <pre>
 * Usage:
 *   java -jar log-analyzer.jar [OPTIONS] &lt;file1&gt; [file2 ...]
 *
 * Options:
 *   --config   &lt;path&gt;   Path to JSON config file
 *   --threads  &lt;n&gt;      Number of consumer threads (default: CPU count)
 *   --output   &lt;path&gt;   Path for report output file
 *   --keyword  &lt;kw&gt;     Add a keyword to search (repeatable)
 *   --realtime          Enable real-time monitoring of first file
 *   --no-compare        Skip single-threaded performance comparison
 *   --help              Print this help message
 * </pre>
 */
public class Main {

    public static void main(String[] args) throws Exception {
        configureLogging();

        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            printUsage();
            return;
        }

        // ── Parse arguments ───────────────────────────────────────────────────
        AnalyzerConfig config = AnalyzerConfig.defaults();
        List<Path>     files  = new ArrayList<>();
        List<String>   extraKeywords = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":
                    config = AnalyzerConfig.fromJson(args[++i]);
                    break;
                case "--threads":
                    config.setThreadPoolSize(Integer.parseInt(args[++i]));
                    break;
                case "--output":
                    config.setOutputFile(args[++i]);
                    break;
                case "--keyword":
                    extraKeywords.add(args[++i]);
                    break;
                case "--realtime":
                    config.setRealtimeMonitoring(true);
                    break;
                case "--no-compare":
                    config.setPerformanceComparison(false);
                    break;
                default:
                    Path p = Paths.get(args[i]);
                    if (!Files.exists(p)) {
                        System.err.println("⚠  File not found: " + p);
                    } else {
                        files.add(p);
                    }
            }
        }

        if (!extraKeywords.isEmpty()) {
            List<String> merged = new ArrayList<>(config.getKeywords());
            merged.addAll(extraKeywords);
            config.setKeywords(merged);
        }

        if (files.isEmpty()) {
            System.err.println("❌  No valid log files specified.");
            printUsage();
            System.exit(1);
        }

        // ── Run ───────────────────────────────────────────────────────────────
        LogAnalyzerEngine engine = new LogAnalyzerEngine(config);
        engine.analyze(files);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("""
            ╔══════════════════════════════════════════════════════╗
            ║      Multithreaded Log Analyzer — Usage              ║
            ╚══════════════════════════════════════════════════════╝

            java -jar log-analyzer.jar [OPTIONS] <file1> [file2 ...]

            OPTIONS:
              --config   <path>   JSON config file (overrides defaults)
              --threads  <n>      Consumer thread count (default: CPU cores)
              --output   <path>   Write report to this file
              --keyword  <kw>     Add extra keyword (repeatable)
              --realtime          Monitor first file in real-time
              --no-compare        Skip single-threaded baseline
              --help              Show this message

            EXAMPLES:
              java -jar log-analyzer.jar app.log
              java -jar log-analyzer.jar --threads 8 --output report.txt app.log error.log
              java -jar log-analyzer.jar --config config/analyzer.json logs/
              java -jar log-analyzer.jar --realtime --no-compare server.log
            """);
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (Handler h : root.getHandlers()) {
            h.setFormatter(new SimpleFormatter() {
                @Override
                public String format(LogRecord r) {
                    return String.format("[%s] %s: %s%n",
                        r.getLevel(), r.getLoggerName().replaceAll(".*\\.", ""), r.getMessage());
                }
            });
        }
    }
}
