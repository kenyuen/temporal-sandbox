# Temporal Sandbox


This project is a sandbox for experimenting with the [Temporal Java SDK](https://github.com/temporalio/java-sdk).  It includes a simple application that demonstrates how to define and run Temporal workflows and activities using Java.

## Overview

This is a simple Java application that demonstrates the basic features of Temporal for building reliable, long-running applications.  This is based on the [Getting Started guide for Java](https://learn.temporal.io/getting_started/java/dev_environment/).

## Prerequisites

*   Java 17 or later
*   Maven 3.6.3 or later
*   Temporal CLI installed on your machine. Follow the [Install the Temporal CLI](https://docs.temporal.io/cli) instructions to install it.
*   A running Temporal Server instance. You can use [Temporal CLI](https://docs.temporal.io/cli) to run a local development server: `temporal server start-dev`
*  (Optional) Temporal Web UI to monitor workflow executions. If you started the Temporal Server with the `--ui-port` flag, you can access the Web UI at `http://localhost:<ui-port>`.
*  (Optional) Docker, if you want to run the Temporal Server in a container instead of using the CLI.

## Building

To build the project, run the following command:

```bash
mvn clean install
```

## Running
```bash
# Start the Temporal Server (if you haven't already)
temporal server start-dev --db-filename temporal-sandbox.db

```





