# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Temporal Java SDK sandbox application demonstrating basic workflow and activity patterns. The project uses Java 17, Maven, and Temporal SDK 1.31.0.

## Build and Test Commands

```bash
# Build the project
mvn clean install

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=HelloWorldWorkflowTest

# Run a single test method
mvn test -Dtest=HelloWorldWorkflowTest#testIntegrationGetGreeting
```

## Running the Application

1. Start the Temporal server:
```bash
temporal server start-dev --db-filename temporal-sandbox.db
```

2. Start the worker (listens for workflows on the task queue):
```bash
mvn exec:java -Dexec.mainClass="helloworldapp.HelloWorldWorker"
```

3. Initiate a workflow execution (in a separate terminal):
```bash
mvn exec:java -Dexec.mainClass="helloworldapp.InitiateHelloWorld"
```

## Temporal Architecture

This codebase follows the standard Temporal pattern with clear separation between Workflows and Activities:

### Core Components

- **Workflow Interface** (`@WorkflowInterface`): Defines the workflow contract with `@WorkflowMethod` for the entry point
- **Workflow Implementation**: Orchestrates activities using activity stubs created via `Workflow.newActivityStub()`. Workflows are deterministic and must not contain non-deterministic operations.
- **Activity Interface** (`@ActivityInterface`): Defines the activity methods
- **Activity Implementation**: Contains the actual business logic that can have side effects (I/O, database calls, external API calls)

### Key Patterns

1. **Task Queue**: Defined in `Shared.HELLO_WORLD_TASK_QUEUE` - used to route workflows to workers
2. **Activity Stubs**: Created in workflow implementations with `ActivityOptions` (must specify timeouts like `setStartToCloseTimeout`)
3. **Worker Registration**: Workers must register both workflow implementation types and activity implementation instances
4. **Client Execution**: Clients create workflow stubs with `WorkflowOptions` (specifying workflow ID and task queue) to start executions

### Testing Approach

The project uses `temporal-testing` with `TestWorkflowRule`:
- **Integration tests**: Use real activity implementations with `TestWorkflowRule`
- **Mocked tests**: Use Mockito to mock activities with `mock(ActivityClass.class, withSettings().withoutAnnotations())`

## Code Style Guidelines (from .github/copilot/prompt.md)

- Apply SOLID principles (Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, Dependency Inversion)
- Use constructor injection over field injection for dependency injection
- Keep code readable with meaningful names, simple design, and appropriate conciseness
- Write Javadocs for public classes and methods
- Use JUnit and Mockito for testing
- Comments should explain *why*, not *what*