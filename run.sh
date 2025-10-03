#!/bin/bash
CLASSPATH=$(./mvnw dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout 2>/dev/null)
java -cp "target/classes:$CLASSPATH" com.practice.urlPoller.Main "$@"
