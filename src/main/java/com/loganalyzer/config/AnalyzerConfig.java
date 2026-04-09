package com.loganalyzer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central configuration — zero external dependencies.
 * Can be loaded from a JSON file or built programmatically.
 */
public class AnalyzerConfig {

    private int    threadPoolSize       = Runtime.getRuntime().availableProcessors();
    private int    queueCapacity        = 2000;
    private int    chunkSizeLines       = 500;
    private List<String> keywords       = new ArrayList<>(Arrays.asList(
                                            "Exception","Error","timeout","fail","OOM"));
    private String outputFile           = "analysis_report.txt";
    private boolean realtimeMonitoring  = false;
    private long   monitoringPollMs     = 500;
    private String logPattern           =
        "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)\\s+" +
        "(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE)\\s+" +
        "\\[?([^\\]]+?)\\]?\\s+([\\w\\.]+)\\s+-\\s+(.*)$";
    private List<String> timestampFormats = new ArrayList<>(Arrays.asList(
                                            "yyyy-MM-dd HH:mm:ss.SSS",
                                            "yyyy-MM-dd HH:mm:ss",
                                            "yyyy-MM-dd'T'HH:mm:ss.SSS",
                                            "yyyy-MM-dd'T'HH:mm:ss"));
    private boolean performanceComparison = true;
    private int    topErrorPatterns      = 10;

    public static AnalyzerConfig defaults() { return new AnalyzerConfig(); }

    public static AnalyzerConfig fromJson(String filePath) throws IOException {
        String json = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
        AnalyzerConfig cfg = new AnalyzerConfig();
        cfg.threadPoolSize        = parseInt(json,  "threadPoolSize",        cfg.threadPoolSize);
        cfg.queueCapacity         = parseInt(json,  "queueCapacity",         cfg.queueCapacity);
        cfg.chunkSizeLines        = parseInt(json,  "chunkSizeLines",        cfg.chunkSizeLines);
        cfg.monitoringPollMs      = parseInt(json,  "monitoringPollMs",      (int)cfg.monitoringPollMs);
        cfg.topErrorPatterns      = parseInt(json,  "topErrorPatterns",      cfg.topErrorPatterns);
        cfg.realtimeMonitoring    = parseBool(json, "realtimeMonitoring",    cfg.realtimeMonitoring);
        cfg.performanceComparison = parseBool(json, "performanceComparison", cfg.performanceComparison);
        cfg.outputFile            = parseStr(json,  "outputFile",            cfg.outputFile);
        cfg.logPattern            = parseStr(json,  "logPattern",            cfg.logPattern);
        List<String> kw = parseStringArray(json, "keywords");
        if (!kw.isEmpty()) cfg.keywords = kw;
        List<String> fmts = parseStringArray(json, "timestampFormats");
        if (!fmts.isEmpty()) cfg.timestampFormats = fmts;
        return cfg;
    }

    private static int parseInt(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }
    private static boolean parseBool(String json, String key, boolean def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : def;
    }
    private static String parseStr(String json, String key, String def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\\","\\").replace("\\\"","\"") : def;
    }
    private static List<String> parseStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        Matcher bm = Pattern.compile("\""+key+"\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(json);
        if (!bm.find()) return result;
        Matcher im = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(bm.group(1));
        while (im.find()) result.add(im.group(1));
        return result;
    }

    public int    getThreadPoolSize()        { return threadPoolSize; }
    public int    getQueueCapacity()         { return queueCapacity; }
    public int    getChunkSizeLines()        { return chunkSizeLines; }
    public List<String> getKeywords()        { return Collections.unmodifiableList(keywords); }
    public String getOutputFile()            { return outputFile; }
    public boolean isRealtimeMonitoring()    { return realtimeMonitoring; }
    public long   getMonitoringPollMs()      { return monitoringPollMs; }
    public String getLogPattern()            { return logPattern; }
    public List<String> getTimestampFormats(){ return Collections.unmodifiableList(timestampFormats); }
    public boolean isPerformanceComparison() { return performanceComparison; }
    public int    getTopErrorPatterns()      { return topErrorPatterns; }

    public void setThreadPoolSize(int n)     { this.threadPoolSize = Math.max(1,n); }
    public void setQueueCapacity(int n)      { this.queueCapacity = Math.max(10,n); }
    public void setChunkSizeLines(int n)     { this.chunkSizeLines = Math.max(1,n); }
    public void setKeywords(List<String> k)  { this.keywords = new ArrayList<>(k); }
    public void setOutputFile(String f)      { this.outputFile = f; }
    public void setRealtimeMonitoring(boolean b)    { this.realtimeMonitoring = b; }
    public void setPerformanceComparison(boolean b) { this.performanceComparison = b; }

    @Override
    public String toString() {
        return String.format("AnalyzerConfig{threads=%d, queue=%d, chunk=%d, keywords=%s, realtime=%b}",
            threadPoolSize, queueCapacity, chunkSizeLines, keywords, realtimeMonitoring);
    }
}
