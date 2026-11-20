plugins {
    kotlin("jvm") version "2.4.21"
    application
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-network:3.6.1")
}

application {
    mainClass = "KotlinBenchKt"
    applicationName = "kotlin-bench"
}
