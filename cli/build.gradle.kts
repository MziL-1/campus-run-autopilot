plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("dev.campusrun.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    // Main.kt parses gateway/settlement JSON; core exposes kotlinx only as `implementation`.
    implementation(libs.kotlinx.serialization.json)
    // ApiClient's public constructor takes OkHttpClient (core keeps okhttp internal).
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // Live suite is isolated by tag and only runs through :cli:liveTest (plan Global Constraints).
        excludeTags("live")
    }
}

// Task 9 brief Step 3: the Review Focus #1 hard gate, one real submission per invocation.
tasks.register<Test>("liveTest") {
    description = "Live end-to-end run against production 7.4.x (requires CAMPUS_* session env)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
}
