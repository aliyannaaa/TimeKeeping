# Yoshii TimeKeeping
**Jetpack Compose | Kotlin | Android OJT Project**

This repository contains a mobile application developed as part of the Training. The app is a dedicated **TimeKeeping System** designed for kiosk attendants, featuring a modern, reactive UI built entirely with **Jetpack Compose**.

## 🚀 Features
* **Modern UI:** Developed with Jetpack Compose for a smooth, declarative user experience.
* **Clock-In/Out System:** Simple, high-visibility interface for recording attendance.
* **Real-time Geolocation:** Captures the attendant's location (e.g., Cebu City) during time logs to ensure proximity.
* **Network Awareness:** Displays the current IP address for network-based verification.
* **Themed Interface:** Custom Material 3 theming with support for brand-specific colors.

## 📂 Project Structure
```text
app/src/main/java/com/example/yoshiitimekeeping/
├── data/           # Data models and logic
├── ui/
│   ├── theme/      # Color.kt, Type.kt, and Theme.kt (Material 3)
│   ├── screens/    # ClockInScreen.kt and UI layouts
│   └── components/ # Reusable UI elements (Buttons, Spacers)
└── MainActivity.kt # Entry point of the application
