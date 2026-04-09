# Multithreaded Log Analyzer

A high-performance, concurrent log analysis system built in Java that efficiently processes GB-scale log files using the Producer-Consumer pattern, thread pools, and lock-free data structures.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        LogAnalyzerEngine                            │
│                                                                     │
│  ┌──────────────────────┐       ┌───────────────────────────────┐  │
│  │   Producer Pool      │       │   Consumer Pool               │  │
│  │                      │       │                               │  │
│  │  LogFileReader #1 ───┼──┐    │  LogProcessor #1              │  │
│  │  LogFileReader #2 ───┼──┤    │  LogProcessor #2              │  │
│  │  LogFileReader #N ───┼──┘    │  LogProcessor #M              │  │
│  └──────────────────────┘   │   └──────────────┬────────────────┘  │
│                              │                  │                   │
│              ┌───────────────▼──────────────┐   │                   │
│              │  BlockingQueue<LogChunk>      │───┘                   │
│              │  (bounded, back-pressure)    │                       │
│              └──────────────────────────────┘                       │
│                                                  ▼                  │
│                                       AnalysisResult                │
│                                       (ConcurrentHashMap +          │
│                                        AtomicLong counters)         │
└─────────────────────────────────────────────────────────────────────┘
```

### Module Breakdown

| Package | Class | Responsibility |
|---|---|---|
| `core` | `LogAnalyzerEngine` | Orchestrates the full pipeline; manages thread pools |
| `reader` | `LogFileReader` | **Producer** — streams file into `LogChunk`s → queue |
| `processor` | `LogProcessor` | **Consumer** — parses chunks, updates `AnalysisResult` |
| `analyzer` | `SingleThreadedAnalyzer` | Baseline for performance comparison |
| `model` | `LogEntry` | Immutable parsed log line (thread-safe by design) |
| `model` | `LogChunk` | Unit of work exchanged via `BlockingQueue` |
| `model` | `AnalysisResult` | Thread-safe accumulator using `AtomicLong` + `ConcurrentHashMap` |
| `config` | `AnalyzerConfig` | JSON-loadable configuration (zero dependencies) |
| `util` | `LogLineParser` | Stateless regex-based log line parser |
| `monitor` | `RealtimeLogMonitor` | `tail -f` style live file monitoring |
| `report` | `ReportGenerator` | Console + file report generation |

---

## Design Decisions

### 1. Producer-Consumer with `BlockingQueue`
The `LinkedBlockingQueue` with a bounded capacity (default 2000 chunks) provides **natural back-pressure**: producers block when the queue is full, preventing out-of-memory errors on very large files. Consumers block when empty, avoiding CPU spin-wait.

```
Producer blocks (file >> memory)     Consumer blocks (IO faster than CPU)
        ↓                                          ↓
[FULL] ■■■■■■■■■■■■■■■■■■■■  ←→  □□□□□□□□□□□□□□□□□□□□ [EMPTY]
```

### 2. Poison Pill Termination
After all producers finish, the engine enqueues exactly **one poison pill per consumer thread**. Each consumer exits cleanly on receipt — no `volatile` flags, no busy-wait, no `interrupt()` storms.

### 3. Lock-Free `AnalysisResult`
All counters use `AtomicLong`; maps use `ConcurrentHashMap`. This eliminates lock contention across consumer threads and allows maximum parallelism with zero synchronization overhead on the critical path.

The only `synchronized` method is `updateTimestampRange()`, which is called infrequently (only once per entry when a timestamp is present) and guards a simple min/max comparison.

### 4. Streaming — Never Load the Full File
`LogFileReader` uses `BufferedReader` with a 8 KB system buffer and streams line-by-line, grouping into `LogChunk`s of configurable size (default 500 lines). At any point only `queueCapacity × chunkSizeLines` lines are held in memory — roughly:

```
2000 chunks × 500 lines × ~150 bytes/line ≈ 150 MB max heap pressure
```

This is constant regardless of file size.

### 5. Immutable `LogEntry`
All fields are `final`; the object is safe to pass between threads without synchronization. The parser is stateless (`LogLineParser`) and can be shared across all consumer threads.

### 6. Thread Pool Sizing
- **Producers**: `min(fileCount, 4)` — IO-bound; too many concurrent disk reads thrash the disk cache
- **Consumers**: `Runtime.getRuntime().availableProcessors()` (configurable) — CPU-bound parsing

### 7. Zero External Dependencies
The project compiles and runs with only the JDK (Java 17+). JSON config parsing uses a hand-written regex-based parser in `AnalyzerConfig`.

---

## Concurrency Patterns Used

| Pattern | Where |
|---|---|
| Producer-Consumer | `LogFileReader` → `BlockingQueue` → `LogProcessor` |
| Poison Pill | `LogChunk.POISON_PILL` for consumer shutdown |
| `CountDownLatch` | `LogAnalyzerEngine` waits for all consumers to finish |
| Thread Pool (`ExecutorService`) | Separate pools for producers and consumers |
| `ConcurrentHashMap` | `AnalysisResult` error patterns, keyword hits, buckets |
| `AtomicLong` | All counters (level counts, total lines, malformed) |
| Immutable value objects | `LogEntry`, `LogChunk` — safe cross-thread sharing |
| Daemon threads | Monitor thread exits automatically with JVM |

---

## Prerequisites

- **Java 17+** (JDK, not just JRE — needs `javac`)
- **Python 3** (optional — for sample log generation)

Install JDK:
```bash
# Ubuntu / Debian
sudo apt install openjdk-17-jdk

# macOS (Homebrew)
brew install openjdk@17

# Windows — download from https://adoptium.net/
```

---

## Build & Run

### Option A — Maven (recommended)
```bash
mvn package -q
java -jar log-analyzer.jar sample_logs/app_1.log
```

### Option B — Build script (no Maven)
```bash
chmod +x build.sh
./build.sh
```

### Option C — Manual javac
```bash
# Compile
find src/main/java -name "*.java" | xargs javac --release 17 -d build/classes

# Run
java -cp build/classes com.loganalyzer.Main sample_logs/app_1.log
```

---

## Usage

```
java -jar log-analyzer.jar [OPTIONS] <file1> [file2 ...]

OPTIONS:
  --config   <path>   JSON config file (see config/analyzer.json)
  --threads  <n>      Consumer thread count (default: CPU core count)
  --output   <path>   Write report to file
  --keyword  <kw>     Add extra search keyword (repeatable)
  --realtime          Enable real-time monitoring of first file
  --no-compare        Skip single-threaded baseline
  --help              Show usage
```

### Examples
```bash
# Single file with defaults
java -jar log-analyzer.jar app.log

# Multiple files, 8 threads, save report
java -jar log-analyzer.jar --threads 8 --output report.txt app1.log app2.log app3.log

# Custom config + extra keywords
java -jar log-analyzer.jar --config config/analyzer.json --keyword "CIRCUIT_BREAKER" app.log

# Real-time monitoring (tail -f style)
java -jar log-analyzer.jar --realtime --no-compare server.log

# Force single-threaded only (no comparison)
java -jar log-analyzer.jar --no-compare --threads 1 big_file.log
```

---

## Configuration (JSON)

```json
{
  "threadPoolSize":       8,
  "queueCapacity":        2000,
  "chunkSizeLines":       500,
  "keywords":             ["Exception", "timeout", "OOM", "deadlock"],
  "outputFile":           "analysis_report.txt",
  "realtimeMonitoring":   false,
  "monitoringPollMs":     500,
  "performanceComparison": true,
  "topErrorPatterns":     10,
  "logPattern":           "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)\\s+(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE)\\s+\\[?([^\\]]+?)\\]?\\s+([\\w\\.]+)\\s+-\\s+(.*)$",
  "timestampFormats":     [
    "yyyy-MM-dd HH:mm:ss.SSS",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss.SSS"
  ]
}
```

---

## Generating Sample Log Files

```bash
# 3 files × 50,000 lines each (~4 MB each)
python3 generate_sample_logs.py

# Custom: 5 files × 500,000 lines each (~40 MB each)
python3 generate_sample_logs.py --lines 500000 --files 5 --outdir big_logs
```

---

## Sample Output

```
══════════════════════════════════════════════════════════════════════════
   MULTITHREADED LOG ANALYZER — ANALYSIS REPORT
══════════════════════════════════════════════════════════════════════════

▶ OVERVIEW
──────────────────────────────────────────────────────────────────────────
  Total Lines Read   : 90,000
  Parsed Entries     : 90,000
  Malformed Lines    : 127
  Earliest Timestamp : 2024-06-01 08:00:00
  Latest Timestamp   : 2024-06-01 23:59:58
  Processing Time    : 312 ms

▶ LOG LEVEL DISTRIBUTION
──────────────────────────────────────────────────────────────────────────
  FATAL    :      45  (  0.1%)  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
  ERROR    :   9,018  ( 10.0%)  ███░░░░░░░░░░░░░░░░░░░░░░░░░░░
  WARN     :  13,527  ( 15.0%)  ████░░░░░░░░░░░░░░░░░░░░░░░░░░
  INFO     :  40,581  ( 45.1%)  █████████████░░░░░░░░░░░░░░░░░
  DEBUG    :  26,829  ( 29.8%)  █████████░░░░░░░░░░░░░░░░░░░░░

▶ TOP ERROR PATTERNS
──────────────────────────────────────────────────────────────────────────
   1. NullPointerException                     2,706 occurrences
   2. TimeoutException                         2,104 occurrences
   3. Timeout/TimedOut                         2,104 occurrences
   4. Connection Failure                       1,803 occurrences
   5. SQLException                             1,201 occurrences

▶ PERFORMANCE COMPARISON
──────────────────────────────────────────────────────────────────────────
  Single-threaded  : 1,847 ms
  Multi-threaded   : 312 ms  (8 threads)
  Speedup          : 5.92x
```

---

## Running Tests

```bash
# Maven
mvn test

# Manual (requires JUnit 5 Platform Launcher on classpath)
javac -cp junit-platform-console-standalone.jar \
      src/test/java/com/loganalyzer/LogAnalyzerTests.java
java  -jar junit-platform-console-standalone.jar \
      --class-path build/classes --scan-class-path
```

Tests cover:
- Log line parsing (happy path, malformed, null, blank)
- Level string normalization (INFO / WARN / WARNING / SEVERE / etc.)
- `AnalysisResult` thread safety (16 concurrent writers)
- `AnalyzerConfig` JSON loading
- Full pipeline end-to-end with in-memory temp file

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| File not found | Logged as `SEVERE`; other files continue |
| Malformed log line | Counted as `malformed`; level set to `UNKNOWN` |
| Partial regex match | Fallback parser salvages the level keyword |
| Producer `InterruptedException` | Thread marks itself interrupted; remaining chunks are abandoned |
| Consumer timeout (10 min) | `LogAnalyzerEngine` logs a warning; report is still generated |
| JSON config parse error | `IOException` propagated to `Main`; program exits with message |
| File rotation (realtime) | `RealtimeLogMonitor` detects truncation; resets file pointer |

---

## Project Structure

```
log-analyzer/
├── src/
│   ├── main/java/com/loganalyzer/
│   │   ├── Main.java                        ← CLI entry point
│   │   ├── core/
│   │   │   └── LogAnalyzerEngine.java       ← Orchestrator
│   │   ├── reader/
│   │   │   └── LogFileReader.java           ← Producer (Runnable)
│   │   ├── processor/
│   │   │   └── LogProcessor.java            ← Consumer (Runnable)
│   │   ├── analyzer/
│   │   │   └── SingleThreadedAnalyzer.java  ← Baseline comparison
│   │   ├── monitor/
│   │   │   └── RealtimeLogMonitor.java      ← tail -f watcher
│   │   ├── model/
│   │   │   ├── LogEntry.java                ← Immutable parsed line
│   │   │   ├── LogChunk.java                ← Queue payload
│   │   │   └── AnalysisResult.java          ← Thread-safe accumulator
│   │   ├── config/
│   │   │   └── AnalyzerConfig.java          ← JSON config (zero deps)
│   │   ├── util/
│   │   │   └── LogLineParser.java           ← Stateless regex parser
│   │   └── report/
│   │       └── ReportGenerator.java         ← Console + file report
│   └── test/java/com/loganalyzer/
│       └── LogAnalyzerTests.java            ← JUnit 5 test suite
├── config/
│   └── analyzer.json                        ← Sample configuration
├── sample_logs/                             ← Generated test files
├── generate_sample_logs.py                  ← Log file generator
├── build.sh                                 ← Zero-Maven build script
├── pom.xml                                  ← Maven build descriptor
└── README.md
```
