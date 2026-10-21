plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.discordlite"
version = "0.0.1"

application {
    mainClass.set("com.discordlite.server.ApplicationKt")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.bouncycastle)
    implementation(libs.angus.mail)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.testcontainers.postgresql)
    // Tests act as real Signal clients so the full encrypt → relay → decrypt path is exercised.
    testImplementation(libs.libsignal.client)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew run` picks up the local secrets in ../.env (git-ignored), so no manual exports.
tasks.named<JavaExec>("run") {
    val envFile = rootProject.file("../.env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .forEach { line -> environment(line.substringBefore('='), line.substringAfter('=')) }
    }
}

// A test friend that replies to whatever you send it, using real Signal encryption.
// Run the server first, then: ./gradlew echoBot
tasks.register<JavaExec>("echoBot") {
    group = "application"
    description = "Runs @echobot, an end-to-end encrypted test friend that echoes your messages."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.discordlite.server.tools.EchoBotKt")
    standardInput = System.`in`
}
