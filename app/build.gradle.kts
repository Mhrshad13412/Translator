plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.floattr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.floattr"
        minSdk = 34
        targetSdk = 35
        // نسخه: زیربنا.قابلیت‌ها.دیباگ‌ها (فقط همین خط رو عوض کن؛ کد نسخه خودش از روی اون ساخته می‌شه)
        // برای ساخته شدن ریلیز جدید هر بار قبل از پوش، این عدد را بالا ببر
        versionName = "1.0.0"
        versionCode = versionName!!.split(".").let { (a, b, c) -> a.toInt() * 1_000_000 + b.toInt() * 1_000 + c.toInt() }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
