# GitHub Copilot Instructions for Temporal Java SDK Projects

This document provides guidelines for using GitHub Copilot in this Temporal Java SDK sandbox project. The project uses Java 17, Maven, and Temporal SDK 1.31.0 to demonstrate basic workflow and activity patterns.

## 1. General Principles

### Clean Code
- **Readability**: Code should be easy to read and understand. Use meaningful names for variables, methods, and classes.
- **Simplicity**: Keep the design simple. Avoid unnecessary complexity.
- **Conciseness**: Write code that is expressive but not overly verbose.

### SOLID Principles
- **Single Responsibility Principle (SRP)**: Each class or method should have one and only one reason to change.
- **Open/Closed Principle (OCP)**: Software entities (classes, modules, functions) should be open for extension but closed for modification.
- **Liskov Substitution Principle (LSP)**: Subtypes must be substitutable for their base types.
- **Interface Segregation Principle (ISP)**: No client should be forced to depend on methods it does not use.
- **Dependency Inversion Principle (DIP)**: High-level modules should not depend on low-level modules. Both should depend on abstractions.

## 2. Build and Run Commands

```bash
# Build the project
mvn clean install

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=HelloWorldWorkflowTest

# Run a single test method
mvn test -Dtest=HelloWorldWorkflowTest#testIntegrationGetGreeting

# Start the Temporal server
temporal server start-dev --db-filename temporal-sandbox.db

# Start the worker
mvn exec:java -Dexec.mainClass="helloworldapp.HelloWorldWorker"

# Initiate a workflow execution
mvn exec:java -Dexec.mainClass="helloworldapp.InitiateHelloWorld"
```

## 3. Temporal Architecture

This codebase follows the standard Temporal pattern with clear separation between Workflows and Activities.

### Core Components
- **Workflow Interface** (`@WorkflowInterface`): Defines the workflow contract with `@WorkflowMethod` for the entry point.
- **Workflow Implementation**: Orchestrates activities using activity stubs created via `Workflow.newActivityStub()`. Workflows are deterministic and must not contain non-deterministic operations.
- **Activity Interface** (`@ActivityInterface`): Defines the activity methods.
- **Activity Implementation**: Contains the actual business logic that can have side effects (I/O, database calls, external API calls).

### Key Patterns
- **Task Queue**: Defined in `Shared.HELLO_WORLD_TASK_QUEUE` — used to route workflows to workers.
- **Activity Stubs**: Created in workflow implementations with `ActivityOptions` (must specify timeouts like `setStartToCloseTimeout`).
- **Worker Registration**: Workers must register both workflow implementation types and activity implementation instances.
- **Client Execution**: Clients create workflow stubs with `WorkflowOptions` (specifying workflow ID and task queue) to start executions.

### Dependency Injection
- Use constructor injection to enforce immutability and make dependencies explicit.
- Avoid field injection as it can lead to issues with testing and immutability.

## 4. Testing

- **Integration Tests**: Use `TestWorkflowRule` from `temporal-testing` with real activity implementations.
- **Mocked Tests**: Use Mockito to mock activities with `mock(ActivityClass.class, withSettings().withoutAnnotations())`.
- **Test Coverage**: Focus on testing critical paths and complex logic.

## 5. Code Style and Formatting

- **Java Conventions**: Adhere to the official Java coding conventions.
- **Comments**: Write comments to explain *why* something is done, not *what* is being done.

## 6. Documentation

- **Javadocs**: Write Javadocs for all public classes and methods.

---

By following these guidelines, you can leverage GitHub Copilot to accelerate development while maintaining a high standard of code quality in this Temporal Java SDK project.
