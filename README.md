# FlowCast - 159.336 Assignment 3

## Introduction

FlowCast is a simple yet modern DLNA casting client for Android. This project was developed for the **159.336 Assignment 3**.

The application allows users to discover DLNA devices on their local Wi-Fi network and cast local media (videos, images, and music) from their phone to a selected device, such as a smart TV or a computer running compatible software.

## Features

-   **Material Design UI**: 
    -   Built with Material Design 3.
-   **Settings Menu**:
    -   Accessible via a long-press app shortcut on the home screen.
    -   Switch between different color themes(Not fully completed).
-   **i18n**: 
    -   Supports both English (default) and Chinese.

## Technologies Used

-   **Language**: Kotlin
-   **Platform**: Android SDK
-   **Core Libraries**:
    -   Cling 2.1.1 (for UPnP/DLNA)
    -   Jetty 8.1.22 (as a Cling dependency)
    -   NanoHTTPD (for local media server)
-   **UI & Components**:
    -   Material Design 3 Components
    -   AndroidX Libraries (AppCompat, SwipeRefreshLayout, Preference)

## How to Build and Run

1.  Clone the repository from GitHub.
2.  Open the project in a recent version of Android Studio.
3.  Allow Gradle to sync and download all the required dependencies.
4.  Build and run the application on a physical Android device connected to the same Wi-Fi network as a DLNA renderer. (Note: Network discovery may be limited or non-functional on the Android Emulator).
