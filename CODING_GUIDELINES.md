# Coding Guidelines

This document outlines the coding standards for M175a Studio.

## Core Standards
- **Language:** Kotlin strictly.
- **UI Toolkit:** Jetpack Compose natively. XML layouts should be avoided.
- **Design System:** Material Design 3 guidelines must be followed.

## Architectural Patterns
- **Architecture:** Follow the MVVM and Repository Pattern.
- **Dependency Injection:** Use Koin for module instantiation.
- **Concurrency:** Prefer Kotlin Coroutines and `StateFlow` for state management and asynchronous operations.
- **Gradle:** Use Gradle Kotlin DSL (`build.gradle.kts`) and a Version Catalog for dependency management.

## Naming & Structure
- Avoid generic prefixes. Everything should be tailored around the M175a device functionality.
- Document complex USB payload constructions to map cleanly to the [USB Protocol](USB_PROTOCOL.md).
- Do not add support for ESC/POS or ZPL integrations.

## Related Documentation
- [Architecture](ARCHITECTURE.md)
- [Contributing](CONTRIBUTING.md)
