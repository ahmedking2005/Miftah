plugins {
    id("com.android.application") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    // Stage 3: embedded CPython for skill scripts (src/main/python).
    id("com.chaquo.python") version "15.0.1" apply false
}
