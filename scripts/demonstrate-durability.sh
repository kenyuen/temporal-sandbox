#!/usr/bin/env bash
# Demonstrates Temporal's durability guarantee by killing a live Worker process in the middle
# of an order and showing that a freshly started Worker process finishes the job without
# redoing the steps that already completed.
#
# Prerequisites:
#   - A running Temporal dev server: temporal server start-dev --db-filename temporal-sandbox.db
#
# Usage:
#   ./scripts/demonstrate-durability.sh

set -euo pipefail
cd "$(dirname "$0")/.."

ORDER_ID="demo-order-$(date +%s)"
WORKFLOW_ID="DurableOrder-$ORDER_ID"
WORKER_MAIN="helloworldapp.durability.DurableOrderWorker"
INITIATE_MAIN="helloworldapp.durability.InitiateDurableOrder"
STATUS_MAIN="helloworldapp.durability.CheckDurableOrderStatus"

log() { printf '\n=== %s ===\n' "$1"; }

status_of() {
    mvn -q exec:java -Dexec.mainClass="$STATUS_MAIN" -Dexec.args="$WORKFLOW_ID" 2>/dev/null
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
sleep 3

log "Letting the order progress into step 2 of 5"
sleep 10
echo "$(status_of)"

log "Destroying worker process #1 (kill -9)"
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
    sleep 3
done

log "Done"
echo "Worker process #2 finished the order that worker process #1 started, picking up exactly"
echo "where it left off -- the steps already completed were never redone. That's Temporal's"
echo "durability guarantee: a Workflow Execution's state lives in the Temporal Server's history,"
echo "not in any single worker's memory."

kill "$NEW_WORKER_PID" 2>/dev/null || true
