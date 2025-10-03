#!/bin/bash

echo "================================================================================
RUNNING URL POLLER - VERIFICATION TEST
================================================================================"

java -cp "target/classes:$(./mvnw dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q 2>/dev/null)" com.practice.urlPoller.Main 2>&1 | grep -E "(Loaded|Starting timer|IP\(groups\)|FpingBatchWorker)" &

MAIN_PID=$!
sleep 18
kill $MAIN_PID 2>/dev/null || true
wait $MAIN_PID 2>/dev/null || true

echo ""
echo "================================================================================"

