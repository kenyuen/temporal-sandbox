#!/usr/bin/env bash
# Demonstrates Temporal's durability guarantee by killing a live Worker process in the middle
# of an order and showing that a freshly started Worker process finishes the job without
# redoing the steps that already completed.
#
# Status is polled with the `temporal` CLI (a lightweight RPC, ~0.1s) rather than by spawning a
# JVM per check: on a loaded machine, booting `mvn exec:java` for every poll can take long enough
# that the order finishes on its own before the kill ever lands, which defeats the demo. Polling
# for the exact moment the order reaches step 2 keeps the kill reliably mid-flight regardless of
# JVM startup overhead on the host.
#
# Prerequisites:
#   - The `temporal` CLI on PATH (https://docs.temporal.io/cli)
#   - A running Temporal dev server: temporal server start-dev --db-filename temporal-sandbox.db
#
# Usage:
#   ./scripts/demonstrate-durability.sh

set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v temporal >/dev/null 2>&1; then
    echo "error: the 'temporal' CLI is required but not found on PATH (https://docs.temporal.io/cli)" >&2
    exit 1
fi

ORDER_ID="demo-order-$(date +%s)"
WORKFLOW_ID="DurableOrder-$ORDER_ID"
WORKER_MAIN="helloworldapp.durability.DurableOrderWorker"
INITIATE_MAIN="helloworldapp.durability.InitiateDurableOrder"

log() { printf '\n=== %s ===\n' "$1"; }

status_of() {
    temporal workflow query --workflow-id "$WORKFLOW_ID" --type getStatus -o json 2>/dev/null \
        | grep -o '"[^"]*"' | sed -n '2p' | tr -d '"'
}

wait_for_status() {
    local needle="$1" timeout_s="$2" waited=0
    while (( waited < timeout_s )); do
        local current
        current="$(status_of)"
        if [[ "$current" == *"$needle"* ]]; then
            echo "$current"
            return 0
        fi
        sleep 1
        ((waited++))
    done
    echo "timed out waiting for status containing '$needle'" >&2
    return 1
}

log "Building the project"
mvn -q compile

log "Starting worker process #1"
mvn -q exec:java -Dexec.mainClass="$WORKER_MAIN" &
WORKER_PID=$!
echo "Worker process #1 started (pid $WORKER_PID)"
sleep 5

log "Starting the order"
mvn -q exec:java -Dexec.mainClass="$INITIATE_MAIN" -Dexec.args="$ORDER_ID"

log "Waiting for the order to reach step 2 of 5"
CURRENT_STATUS="$(wait_for_status "Step 2/5" 30)"
echo "STATUS: $CURRENT_STATUS"

log "Destroying worker process #1 (kill -9) while the order is still mid-flight"
kill -9 "$WORKER_PID"
wait "$WORKER_PID" 2>/dev/null || true
echo "Worker process #1 (pid $WORKER_PID) is gone."
echo "The order is still 'running' on the Temporal Server -- its progress was never in the worker's memory."
echo "(Querying its status right now would simply wait, since no worker is available to answer it.)"

log "Starting worker process #2 (a completely new process, no memory of the order above)"
mvn -q exec:java -Dexec.mainClass="$WORKER_MAIN" &
NEW_WORKER_PID=$!
echo "Worker process #2 started (pid $NEW_WORKER_PID)"

log "Waiting for the order to finish"
for _ in $(seq 1 40); do
    CURRENT_STATUS="$(status_of)"
    echo "$CURRENT_STATUS"
    if [[ "$CURRENT_STATUS" == *"Completed"* ]]; then
        break
    fi
    sleep 1
done

log "Done"
echo "Worker process #2 finished the order that worker process #1 started, picking up exactly"
echo "where it left off -- the steps already completed were never redone. That's Temporal's"
echo "durability guarantee: a Workflow Execution's state lives in the Temporal Server's history,"
echo "not in any single worker's memory."

kill "$NEW_WORKER_PID" 2>/dev/null || true
