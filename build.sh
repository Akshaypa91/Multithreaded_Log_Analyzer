#!/usr/bin/env bash
# ============================================================
# build.sh — Compile and run the Multithreaded Log Analyzer
# Requires: Java 17+ (JDK with javac)
# ============================================================
set -euo pipefail

JAVA_SRC_ROOT="src/main/java"
OUT_DIR="build/classes"
JAR_NAME="log-analyzer.jar"
MAIN_CLASS="com.loganalyzer.Main"

# ── Locate javac ─────────────────────────────────────────────
if ! command -v javac &>/dev/null; then
  # Try common JDK locations
  for candidate in \
      /usr/lib/jvm/java-21-openjdk-amd64/bin \
      /usr/lib/jvm/java-17-openjdk-amd64/bin \
      /usr/local/lib/jvm/jdk-17/bin \
      "$JAVA_HOME/bin"; do
    if [ -x "$candidate/javac" ]; then
      export PATH="$candidate:$PATH"
      break
    fi
  done
fi

if ! command -v javac &>/dev/null; then
  echo "❌  javac not found. Install JDK 17+:"
  echo "    Ubuntu/Debian: sudo apt install openjdk-17-jdk"
  echo "    macOS:         brew install openjdk@17"
  echo "    Windows:       https://adoptium.net/"
  exit 1
fi

JAVA_VERSION=$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)
echo "✔  javac $JAVA_VERSION found"

# ── Compile ───────────────────────────────────────────────────
echo "⚙  Compiling..."
mkdir -p "$OUT_DIR"

# Collect all .java sources
SOURCES=$(find "$JAVA_SRC_ROOT" -name "*.java" | tr '\n' ' ')

# shellcheck disable=SC2086
javac -encoding UTF-8 \
      --release 17 \
      -d "$OUT_DIR" \
      $SOURCES

echo "✔  Compilation successful"

# ── Package JAR ───────────────────────────────────────────────
echo "📦 Packaging $JAR_NAME..."
cat > "$OUT_DIR/META-INF/MANIFEST.MF" << MANIFEST
Manifest-Version: 1.0
Main-Class: $MAIN_CLASS
MANIFEST

mkdir -p "$OUT_DIR/META-INF"
jar --create \
    --file="$JAR_NAME" \
    --main-class="$MAIN_CLASS" \
    -C "$OUT_DIR" .

echo "✔  Built: $JAR_NAME ($(du -h "$JAR_NAME" | cut -f1))"

# ── Generate sample logs if needed ───────────────────────────
if [ ! -d "sample_logs" ]; then
  echo "📝 Generating sample log files..."
  python3 generate_sample_logs.py --lines 50000 --files 3
fi

# ── Run ───────────────────────────────────────────────────────
echo ""
echo "🚀 Running analysis on sample_logs/..."
echo "   Command: java -jar $JAR_NAME --config config/analyzer.json sample_logs/*.log"
echo ""
java -jar "$JAR_NAME" \
     --config config/analyzer.json \
     sample_logs/app_1.log \
     sample_logs/app_2.log \
     sample_logs/app_3.log

echo ""
echo "✅  Done. Report written to: analysis_report.txt"
