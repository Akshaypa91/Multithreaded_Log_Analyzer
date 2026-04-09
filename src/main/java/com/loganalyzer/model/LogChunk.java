package com.loganalyzer.model;

import java.util.Collections;
import java.util.List;

/**
 * A batch of raw log lines produced by the reader and consumed by processors.
 * Immutable container — safe to share across threads.
 */
public final class LogChunk {

    /** Sentinel value signalling end-of-stream to consumers. */
    public static final LogChunk POISON_PILL = new LogChunk(Collections.emptyList(), "POISON", 0, 0);

    private final List<String> lines;
    private final String       sourceFile;
    private final long         startLine;   // 1-based line number of first line in chunk
    private final int          chunkId;

    public LogChunk(List<String> lines, String sourceFile, long startLine, int chunkId) {
        this.lines      = Collections.unmodifiableList(lines);
        this.sourceFile = sourceFile;
        this.startLine  = startLine;
        this.chunkId    = chunkId;
    }

    public List<String> getLines()      { return lines; }
    public String       getSourceFile() { return sourceFile; }
    public long         getStartLine()  { return startLine; }
    public int          getChunkId()    { return chunkId; }
    public boolean      isPoisonPill()  { return this == POISON_PILL; }

    @Override
    public String toString() {
        return String.format("LogChunk{id=%d, file=%s, startLine=%d, lines=%d}",
            chunkId, sourceFile, startLine, lines.size());
    }
}
