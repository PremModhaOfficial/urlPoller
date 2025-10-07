#!/bin/bash
# Thread Count Verification Script
# Usage: ./verify_threads.sh [config_file]

set -e

CONFIG_FILE="${1:-urls_thread_test.txt}"
LOG_FILE="/tmp/urlpoller_thread_verify.log"
TIMEOUT=15

echo "╔════════════════════════════════════════════════════════════╗"
echo "║        URL Poller Thread Optimization Verification        ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "Configuration: $CONFIG_FILE"
echo "Log file: $LOG_FILE"
echo "Test duration: ${TIMEOUT}s"
echo ""

# Clean up any existing processes
pkill -f "com.practice.urlPoller.Main" 2>/dev/null || true
sleep 1

# Start application
echo "▶ Starting application..."
java -cp "target/classes:$(./mvnw dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout -q 2>/dev/null)" \
  com.practice.urlPoller.Main "$CONFIG_FILE" > "$LOG_FILE" 2>&1 &

APP_PID=$!
echo "  PID: $APP_PID"

# Wait for startup and execution
echo "▶ Waiting ${TIMEOUT}s for fping cycles..."
sleep $TIMEOUT

# Check if still running
if ! ps -p $APP_PID > /dev/null 2>&1; then
    echo "✗ Application crashed! Check logs:"
    tail -20 "$LOG_FILE"
    exit 1
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "                    CONFIGURATION                          "
echo "═══════════════════════════════════════════════════════════"
grep -E "(Vertx options|Created fping|Parsed .* total)" "$LOG_FILE"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "                   THREAD ANALYSIS                         "
echo "═══════════════════════════════════════════════════════════"

# Get thread counts
EVENTLOOP=$(jstack $APP_PID 2>/dev/null | grep -c 'vert\.x-eventloop' || echo 0)
WORKERS=$(jstack $APP_PID 2>/dev/null | grep -c 'vert\.x-worker-thread' || echo 0)
INTERNAL=$(jstack $APP_PID 2>/dev/null | grep -c 'vert\.x-internal-blocking' || echo 0)
FPING=$(jstack $APP_PID 2>/dev/null | grep -cE 'fping-worker|fping-batch' || echo 0)
TOTAL=$((EVENTLOOP + WORKERS + INTERNAL + FPING))

printf "  %-30s %2d threads\n" "Event loops:" "$EVENTLOOP"
printf "  %-30s %2d threads\n" "Default worker pool:" "$WORKERS"
printf "  %-30s %2d threads\n" "Internal blocking pool:" "$INTERNAL"
printf "  %-30s %2d threads\n" "Fping worker pool:" "$FPING"
echo "  ───────────────────────────────────────────────────────"
printf "  %-30s %2d threads\n" "TOTAL Vert.x threads:" "$TOTAL"

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "                   VERIFICATION                            "
echo "═══════════════════════════════════════════════════════════"

# Expected values
EXPECTED_EVENTLOOP=2
EXPECTED_WORKERS=1
EXPECTED_INTERNAL=1
EXPECTED_FPING=3
EXPECTED_TOTAL=7

PASS=true

# Verify each pool
if [ $EVENTLOOP -eq $EXPECTED_EVENTLOOP ]; then
    echo "  ✓ Event loops: $EVENTLOOP (expected: $EXPECTED_EVENTLOOP)"
else
    echo "  ✗ Event loops: $EVENTLOOP (expected: $EXPECTED_EVENTLOOP)"
    PASS=false
fi

if [ $WORKERS -eq $EXPECTED_WORKERS ]; then
    echo "  ✓ Default workers: $WORKERS (expected: $EXPECTED_WORKERS)"
else
    echo "  ✗ Default workers: $WORKERS (expected: $EXPECTED_WORKERS)"
    PASS=false
fi

if [ $INTERNAL -eq $EXPECTED_INTERNAL ]; then
    echo "  ✓ Internal blocking: $INTERNAL (expected: $EXPECTED_INTERNAL)"
else
    echo "  ✗ Internal blocking: $INTERNAL (expected: $EXPECTED_INTERNAL)"
    PASS=false
fi

# Fping workers are on-demand, allow 0-3
if [ $FPING -le $EXPECTED_FPING ]; then
    echo "  ✓ Fping workers: $FPING (expected: 0-$EXPECTED_FPING, on-demand)"
else
    echo "  ✗ Fping workers: $FPING (expected: 0-$EXPECTED_FPING)"
    PASS=false
fi

if [ $TOTAL -le $EXPECTED_TOTAL ]; then
    echo "  ✓ Total threads: $TOTAL (expected: ≤$EXPECTED_TOTAL)"
else
    echo "  ✗ Total threads: $TOTAL (expected: ≤$EXPECTED_TOTAL)"
    PASS=false
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "                     COMPARISON                            "
echo "═══════════════════════════════════════════════════════════"
echo "  Before optimization:  28 threads (2+5+1+20)"
echo "  After optimization:    7 threads (2+1+1+3)"
echo "  Current measurement:   $TOTAL threads"
echo ""

SAVINGS=$((28 - TOTAL))
PERCENTAGE=$((SAVINGS * 100 / 28))
echo "  Thread reduction: $SAVINGS threads saved ($PERCENTAGE%)"

# Check for errors in logs
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "                   ERROR CHECKING                          "
echo "═══════════════════════════════════════════════════════════"

ERROR_COUNT=$(grep -ci "error\|exception\|failed" "$LOG_FILE" 2>/dev/null || echo 0)
if [ "$ERROR_COUNT" -gt 0 ]; then
    echo "  ⚠ Found $ERROR_COUNT error messages in logs"
    echo ""
    echo "  Sample errors:"
    grep -i "error\|exception\|failed" "$LOG_FILE" | head -5 | sed 's/^/    /'
    PASS=false
else
    echo "  ✓ No errors found in logs"
fi

# Cleanup
echo ""
echo "▶ Stopping application..."
kill $APP_PID 2>/dev/null
wait 2>/dev/null

echo ""
echo "═══════════════════════════════════════════════════════════"
if [ "$PASS" = true ]; then
    echo "                 ✅ ALL CHECKS PASSED                     "
    echo "═══════════════════════════════════════════════════════════"
    echo ""
    echo "Thread optimization successful! Safe to deploy."
    exit 0
else
    echo "                 ❌ SOME CHECKS FAILED                    "
    echo "═══════════════════════════════════════════════════════════"
    echo ""
    echo "Review the failures above. Check logs at: $LOG_FILE"
    exit 1
fi
