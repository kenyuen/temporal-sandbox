# GitHub Copilot Instructions for Spring Boot Projects

This document provides guidelines for using GitHub Copilot in Spring Boot projects, with a strong emphasis on Clean Code and SOLID principles. The goal is to generate high-quality, maintainable, and robust code.

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

## 2. Spring Boot Specifics

### Project Structure
- Follow the standard Maven/Gradle project structure.
- Group classes by feature or domain to promote modularity.

### Component Design
- **@Service**: Implement business logic. Should be stateless.
- **@Repository**: Encapsulate data access logic. Use Spring Data JPA.
- **@RestController**: Handle HTTP requests. Keep it thin and delegate business logic to services.
- **@Configuration**: Define Spring beans and application configuration.

### Dependency Injection
- Use constructor injection to enforce immutability and make dependencies explicit.
- Avoid field injection as it can lead to issues with testing and immutability.

## 3. Code Style and Formatting

- **Java Conventions**: Adhere to the official Java coding conventions.
- **Formatting**: Use a consistent code formatter (e.g., Prettier for Java, or the IDE's built-in formatter).
- **Comments**: Write comments to explain *why* something is done, not *what* is being done.

## 4. Testing

- **Unit Tests**: Use JUnit and Mockito to write unit tests for services and components.
- **Integration Tests**: Use `@SpringBootTest` to write integration tests that cover the interaction between different parts of the application.
- **Test Coverage**: Aim for high test coverage, but focus on testing critical paths and complex logic.

## 5. Error Handling

- **Custom Exceptions**: Create custom exceptions to represent specific error conditions in the application.
- **@ControllerAdvice**: Use a global exception handler (`@ControllerAdvice`) to handle exceptions and return consistent HTTP responses.
- **Validation**: Use Bean Validation (`javax.validation`) to validate input data.

## 6. Documentation

- **Javadocs**: Write Javadocs for all public classes, methods, and non-trivial private methods.
- **README**: Always update the `README.md` to reflect the current context of the project, including build, run, and test instructions.
- **API Documentation**: Use a tool like Springdoc-openapi to automatically generate OpenAPI 3 documentation for your REST APIs.

---

By following these guidelines, you can leverage GitHub Copilot to accelerate development while maintaining a high standard of code quality in your Spring Boot projects.


