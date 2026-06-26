# Software Architecture

The M175a Studio Android application follows a modern, scalable architecture approach.

## Core Technologies
- **UI Toolkit:** Jetpack Compose (Material Design 3)
- **Architecture Pattern:** MVVM (Model-View-ViewModel)
- **State Management:** StateFlow & Coroutines
- **Dependency Injection:** Koin
- **Build System:** Gradle Kotlin DSL with Version Catalog
- **Language:** Kotlin

## Structure Overview
The application is structured around a clean architecture approach prioritizing separation of concerns between UI, domain logic, and USB device communication.

- **UI Layer:** Implemented using Jetpack Compose. Observes `StateFlow` from ViewModels.
- **Presentation Layer:** MVVM utilizing ViewModels to manage UI state and handle user intents.
- **Domain/Data Layer:** Manages device states, print/scan jobs, and history using the Repository Pattern.
- **Hardware Layer:** Implements the custom USB host architecture for the M175a.

## Related Documentation
- [Coding Guidelines](CODING_GUIDELINES.md)
- [USB Protocol](USB_PROTOCOL.md)
