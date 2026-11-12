#!/usr/bin/env bash
set -euo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$BASE/blue-java-better"
./gradlew --no-parallel check
cd "$BASE/myos-aws-test-better"
./gradlew --no-parallel check installDist qualificationJar
cd "$BASE/myos-simplified"
./gradlew --no-parallel test clausesTest scaleTest
./gradlew --no-parallel orderScale -PgasSchedule=ADDITIVE_PROTOTYPE -PqualificationOrders=10,100,1000 -PqualificationSamples=5 -PqualificationOutput=build/audit-reproduction-a
./gradlew --no-parallel orderScale -PgasSchedule=ADDITIVE_PROTOTYPE -PqualificationOrders=10,100,1000 -PqualificationSamples=5 -PqualificationOutput=build/audit-reproduction-b
