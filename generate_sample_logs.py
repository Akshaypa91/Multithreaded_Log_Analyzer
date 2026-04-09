#!/usr/bin/env python3
"""
generate_sample_logs.py
Generates realistic sample log files for testing the Log Analyzer.

Usage:
    python3 generate_sample_logs.py                    # generate all samples
    python3 generate_sample_logs.py --lines 100000     # custom size
"""

import random
import sys
import argparse
from datetime import datetime, timedelta

LEVELS   = ["DEBUG", "INFO", "INFO", "INFO", "WARN", "WARN", "ERROR", "ERROR", "FATAL"]
THREADS  = ["main", "http-nio-8080-exec-1", "http-nio-8080-exec-2", "scheduler-1",
            "kafka-consumer-1", "db-pool-3", "async-executor-4", "worker-thread-7"]
LOGGERS  = ["com.example.UserService", "com.example.OrderController",
            "com.example.PaymentGateway", "com.example.DatabasePool",
            "com.example.CacheManager", "org.hibernate.SQL",
            "org.springframework.web.servlet.DispatcherServlet",
            "com.example.KafkaConsumer", "com.example.AuthFilter"]

INFO_MSGS = [
    "User login successful for userId={}",
    "Processing order orderId={} for customerId={}",
    "Cache hit for key=session:{}",
    "Scheduled job '{}' started",
    "HTTP GET /api/v1/products returned 200 in {}ms",
    "Database connection acquired from pool (active={}/{})",
    "Kafka message consumed from topic '{}' partition={}",
    "JWT token validated for subject={}",
    "Email notification sent to user {}",
    "Payment processed successfully txnId={}",
]

WARN_MSGS = [
    "Slow query detected ({}ms): SELECT * FROM orders WHERE customer_id={}",
    "Cache eviction triggered, size exceeded threshold ({})",
    "Retry attempt {}/3 for remote service call",
    "Connection pool near capacity ({}/{}), consider increasing pool size",
    "Deprecated API endpoint /api/v1/legacy called by {}",
    "High memory usage detected: {}% of heap used",
    "Rate limit approaching for clientId={}",
]

ERROR_MSGS = [
    "NullPointerException in UserService.getUserById at line 142",
    "Connection refused to database host db-primary:5432",
    "SQLException: deadlock detected on table 'orders'",
    "OutOfMemoryError: Java heap space",
    "TimeoutException: Remote call to payment-service timed out after 5000ms",
    "Connection reset by peer: socket write error",
    "IllegalArgumentException: Invalid parameter 'userId'=null",
    "Failed to deserialize Kafka message: com.fasterxml.jackson.core.JsonParseException",
    "HTTP 500 Internal Server Error for POST /api/v1/checkout",
    "Authentication failed: invalid credentials for user={}",
]

FATAL_MSGS = [
    "Critical database connection pool exhausted — application cannot serve requests",
    "Unrecoverable error: file system full at /var/log/app",
    "JVM crash detected: SIGSEGV in native thread",
]

def random_msg(level):
    r = random.randint(1000, 9999)
    if level == "DEBUG":
        return f"Entering method processRequest with params=[{r}, 'active']"
    elif level == "INFO":
        tpl = random.choice(INFO_MSGS)
        return tpl.replace("{}", str(r))
    elif level == "WARN":
        tpl = random.choice(WARN_MSGS)
        return tpl.replace("{}", str(r))
    elif level in ("ERROR",):
        return random.choice(ERROR_MSGS).replace("{}", str(r))
    elif level == "FATAL":
        return random.choice(FATAL_MSGS)
    return "Unknown log message"

def generate(path, n_lines, start_dt):
    dt = start_dt
    with open(path, "w") as f:
        for i in range(n_lines):
            dt += timedelta(milliseconds=random.randint(1, 500))
            ts  = dt.strftime("%Y-%m-%d %H:%M:%S.") + f"{random.randint(0,999):03d}"
            lvl = random.choice(LEVELS)
            thr = random.choice(THREADS)
            log = random.choice(LOGGERS)
            msg = random_msg(lvl)
            line = f"{ts} {lvl:<5} [{thr}] {log} - {msg}\n"
            f.write(line)
            # Occasionally add a multi-line stack trace for errors
            if lvl in ("ERROR", "FATAL") and random.random() < 0.3:
                f.write(f"\tat com.example.Service.method(Service.java:{random.randint(10,500)})\n")
                f.write(f"\tat com.example.Controller.handle(Controller.java:{random.randint(10,300)})\n")
                if random.random() < 0.5:
                    f.write(f"\tCaused by: java.lang.RuntimeException: underlying cause\n")

def main():
    parser = argparse.ArgumentParser(description="Generate sample log files")
    parser.add_argument("--lines", type=int, default=50000,
                        help="Number of log lines per file (default: 50000)")
    parser.add_argument("--files", type=int, default=3,
                        help="Number of files to generate (default: 3)")
    parser.add_argument("--outdir", default="sample_logs",
                        help="Output directory (default: sample_logs)")
    args = parser.parse_args()

    import os
    os.makedirs(args.outdir, exist_ok=True)

    base_dt = datetime(2024, 6, 1, 8, 0, 0)
    for i in range(1, args.files + 1):
        path = os.path.join(args.outdir, f"app_{i}.log")
        dt_offset = base_dt + timedelta(hours=(i - 1) * 8)
        print(f"Generating {path} ({args.lines:,} lines)...", end=" ", flush=True)
        generate(path, args.lines, dt_offset)
        size_mb = os.path.getsize(path) / (1024 * 1024)
        print(f"done ({size_mb:.1f} MB)")

    print(f"\n✔  {args.files} log file(s) written to '{args.outdir}/'")

if __name__ == "__main__":
    main()
