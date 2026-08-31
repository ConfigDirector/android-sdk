#!/usr/bin/env sh
#
# Fails when the Java unit tests did not run.
#
# The Java source set is what keeps the SDK's Java surface honest: a Kotlin test cannot tell whether
# an API is callable from Java at all. If those tests stop running -- a source set dropped from the
# build, a renamed directory -- the Kotlin suite stays green and nothing says a word. This is what
# says it.

set -eu

results="configdirector-android/build/test-results/testDebugUnitTest"

if [ ! -d "$results" ]; then
    echo "No unit test results at $results. Run ./gradlew build first." >&2
    exit 1
fi

classes=0
tests=0

for file in "$results"/TEST-*JavaTest.xml; do
    [ -e "$file" ] || continue
    classes=$((classes + 1))
    in_file=$(grep -o 'tests="[0-9]*"' "$file" | head -1 | tr -dc '0-9')
    tests=$((tests + ${in_file:-0}))
done

if [ "$classes" -eq 0 ] || [ "$tests" -eq 0 ]; then
    echo "The Java unit tests did not run." >&2
    echo "  Expected $results/TEST-*JavaTest.xml, reporting at least one test." >&2
    echo "  Kotlin tests cannot cover the Java surface, so a green build without them means nothing." >&2
    exit 1
fi

echo "Java unit tests ran: $tests tests across $classes classes."
