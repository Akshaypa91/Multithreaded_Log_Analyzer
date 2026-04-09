package com.loganalyzer.analyzer;

import com.loganalyzer.model.AnalysisResult;
import com.loganalyzer.model.LogEntry;
import com.loganalyzer.util.LogLineParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baseline single-threaded analyzer used solely for performance comparison.
 * Processes files sequentially line-by-line.
 */
public class SingleThreadedAnalyzer {

    private static final Logger LOG = Logger.getLogger(SingleThreadedAnalyzer.class.getName());

    private static final Pattern NPE_PAT  = Pattern.compile("NullPointerException", Pattern.CASE_INSENSITIVE);
    private static final Pattern OOM_PAT  = Pattern.compile("OutOfMemoryError",     Pattern.CASE_INSENSITIVE);
    private static final Pattern ERR_PAT  = Pattern.compile("\\b(\\w*(Exception|Error))\\b");

    private final LogLineParser parser;
    private final List<String>  keywords;

    public SingleThreadedAnalyzer(LogLineParser parser, List<String> keywords) {
        this.parser   = parser;
        this.keywords = keywords;
    }

    public AnalysisResult analyze(List<Path> files) {
        AnalysisResult result = new AnalysisResult();
        long start = System.currentTimeMillis();

        for (Path path : files) {
            LOG.info("[Single] Analyzing: " + path.getFileName());
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8))) {

                String rawLine;
                long lineNum = 0;
                while ((rawLine = br.readLine()) != null) {
                    lineNum++;
                    result.incrementTotalLines();

                    LogEntry entry = parser.parse(rawLine, lineNum, path.getFileName().toString());
                    result.incrementLevel(entry.getLevel());
                    result.incrementProcessed();
                    result.incrementFileCount(path.getFileName().toString());
                    result.updateTimestampRange(entry.getTimestamp());

                    if (entry.getTimestamp() != null) {
                        String bucket = entry.getTimestamp()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH"));
                        result.incrementHourlyBucket(bucket);
                    }

                    if (entry.getLevel() == LogEntry.Level.ERROR || entry.getLevel() == LogEntry.Level.FATAL) {
                        if (NPE_PAT.matcher(entry.getMessage()).find()) result.incrementErrorPattern("NullPointerException");
                        if (OOM_PAT.matcher(entry.getMessage()).find()) result.incrementErrorPattern("OutOfMemoryError");
                        Matcher m = ERR_PAT.matcher(entry.getMessage());
                        if (m.find()) result.incrementErrorPattern(m.group(1));
                    }

                    for (String kw : keywords) {
                        if (rawLine.toLowerCase().contains(kw.toLowerCase())) result.incrementKeyword(kw);
                    }
                }
            } catch (IOException e) {
                LOG.severe("[Single] Error reading " + path + ": " + e.getMessage());
            }
        }

        result.setProcessingTimeMs(System.currentTimeMillis() - start);
        return result;
    }
}
