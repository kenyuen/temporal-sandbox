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

# Each worker's output goes to its own log so the script can react to what it has actually done,
# rather than guessing with fixed sleeps that JVM startup times make unreliable.
LOG_DIR="$(mktemp -d)"
WORKER1_LOG="$LOG_DIR/worker1.log"
WORKER2_LOG="$LOG_DIR/worker2.log"
INITIATE_LOG="$LOG_DIR/initiate.log"
PIDS_TO_STOP=()

# Waits for each process too, so no worker outlives the script; the redirect hides bash's
# "Terminated" job notices.
cleanup() {
    {
        for pid in "${PIDS_TO_STOP[@]+"${PIDS_TO_STOP[@]}"}"; do
            kill "$pid" || true
        done
        for pid in "${PIDS_TO_STOP[@]+"${PIDS_TO_STOP[@]}"}"; do
            wait "$pid" || true
        done
    } 2>/dev/null
    rm -rf "$LOG_DIR"
}
trap cleanup EXIT

log() { printf '\n=== %s ===\n' "$1"; }

status_of() {
    mvn -q exec:java -Dexec.mainClass="$STATUS_MAIN" -Dexec.args="$WORKFLOW_ID" 2>/dev/null
}

# Waits until a line containing $2 appears in log $1, failing after $3 seconds.
wait_for_line() {
    local file="$1" text="$2" timeout="$3"
    for _ in $(seq 1 "$timeout"); do
        if grep -qF "$text" "$file" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out after ${timeout}s waiting for '$text' in $file" >&2
    exit 1
}

# Runs a worker in the background with its output in log $1, echoing that output live.
# `mvn` execs the JVM, so $! is the worker process itself and kill -9 hits it directly.
start_worker() {
    local logfile="$1"
    : > "$logfile"
    mvn -q exec:java -Dexec.mainClass="$WORKER_MAIN" > "$logfile" 2>&1 &
    STARTED_PID=$!
    PIDS_TO_STOP+=("$STARTED_PID")
    # Process substitution keeps $! pointing at tail itself, so cleanup can stop it.
    tail -n +1 -f "$logfile" 2>/dev/null > >(grep --line-buffered -E 'worker|Exception') &
    PIDS_TO_STOP+=("$!")
}

# Any other worker on the task queue (e.g. one started by hand) would pick up steps meant for the
# workers below and make the demo's story wrong.
if OTHER_WORKERS="$(pgrep -f "$WORKER_MAIN")"; then
    echo "Another durable order worker is already running (pid $(echo "$OTHER_WORKERS" | paste -sd ' ' -));" \
        "stop it first, or it will take steps meant for this demo's workers." >&2
    exit 1
fi

log "Building the project"
mvn -q compile

log "Starting worker process #1"
start_worker "$WORKER1_LOG"
WORKER_PID=$STARTED_PID
wait_for_line "$WORKER1_LOG" "Durable order worker starting" 60

log "Starting the order"
# In the background: exec:java keeps this JVM alive for several seconds after the workflow has
# started, and the order must be watched (and the kill timed) while that happens.
mvn -q exec:java -Dexec.mainClass="$INITIATE_MAIN" -Dexec.args="$ORDER_ID" > "$INITIATE_LOG" 2>&1 &
PIDS_TO_STOP+=("$!")
wait_for_line "$INITIATE_LOG" "Started workflow" 60
grep -vE '^WARNING' "$INITIATE_LOG"

log "Letting the order progress through step 2 of 5"
wait_for_line "$WORKER1_LOG" "Reserved inventory for $ORDER_ID" 60
# Give the step's result a moment to reach the server. The workflow then waits 8s before step 3,
# so the kill below lands with steps 3-5 still to do.
sleep 1

log "Destroying worker process #1 (kill -9)"
kill -9 "$WORKER_PID"
wait "$WORKER_PID" 2>/dev/null || true
echo "Worker process #1 (pid $WORKER_PID) is gone."
echo "The order is still 'running' on the Temporal Server -- its progress was never in the worker's memory."
echo "(Querying its status right now would simply wait, since no worker is available to answer it.)"

log "Starting worker process #2 (a completely new process, no memory of the order above)"
start_worker "$WORKER2_LOG"
echo "Worker process #2 started (pid $STARTED_PID)"

log "Waiting for the order to finish"
wait_for_line "$WORKER2_LOG" "Sent confirmation for $ORDER_ID" 120
status_of

log "Checking which worker did which step"
# Each step must have run exactly where the story says, or the demo didn't show what it claims.
mismatches=0
check_step() {
    local step="$1" expected_log="$2" other_log="$3" expected_name="$4"
    if grep -qF "$step for $ORDER_ID" "$expected_log" && ! grep -qF "$step for $ORDER_ID" "$other_log"; then
        echo "'$step' ran once, on $expected_name"
    else
        echo "UNEXPECTED: '$step' did not run only on $expected_name"
        mismatches=$((mismatches + 1))
    fi
}
for step in "Validated order" "Reserved inventory"; do
    check_step "$step" "$WORKER1_LOG" "$WORKER2_LOG" "worker #1"
done
for step in "Charged payment" "Shipped order" "Sent confirmation"; do
    check_step "$step" "$WORKER2_LOG" "$WORKER1_LOG" "worker #2"
done
if [[ $mismatches -ne 0 ]]; then
    echo "The kill did not land between steps 2 and 3, so this run doesn't demonstrate recovery." >&2
    exit 1
fi

log "Done"
echo "Worker process #2 finished the order that worker process #1 started, picking up exactly"
echo "where it left off -- the steps already completed were never redone. That's Temporal's"
echo "durability guarantee: a Workflow Execution's state lives in the Temporal Server's history,"
echo "not in any single worker's memory."
