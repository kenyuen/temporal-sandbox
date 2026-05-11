# Phased Dependency Upgrade
1. Confirm current phase and target versions with the user.
2. Create a branch named `upgrade/<phase>`.
3. Make minimal changes for this phase only.
4. Run `mvn test` (or `./gradlew test`) and fix failures.
5. Commit with message `chore(deps): <phase summary>` and stop for review before next phase.
