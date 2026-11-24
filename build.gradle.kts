import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    // Toolchain 21 compiles the code...
    jvmToolchain(21)
}

// ...but the bytecode must be Java 17: sinceBuild 241 is IDEA 2024.1, which
// runs on JBR 17 (2024.2 is the first JBR 21 release). A class file version
// 61+ fails there with UnsupportedClassVersionError.
//
// This is set on the TASKS, not on the `kotlin { compilerOptions { } }`
// extension: `jvmToolchain(21)` derives the task's jvmTarget from the toolchain
// and the KGP JVM-target consistency check reads the task value, so an
// extension-level setting is silently ignored and the build fails with
// "Inconsistent JVM-target compatibility ... compileJava (17) and
// compileKotlin (21)". -Xjdk-release also blocks accidental use of Java 18+ APIs.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IC on purpose: see gradle.properties. Compiling against Community
        // keeps us off Ultimate-only APIs so the plugin also loads in IC.
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        pluginVerifier()
        // NOTE: deliberately NO testFramework(TestFrameworkType.Platform).
        // Tests in src/test are pure JVM (no IDE fixture) and run on JUnit 5.
        // Adding the platform test framework registers a JUnit 5
        // LauncherSessionListener (com.intellij.tests.JUnit5TestSessionListener)
        // that fails to load without the full IDE runtime classpath and kills
        // the whole test task at startup.
    }

    // JUnit 5 runs our tests.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // JUnit 4 is still needed on the runtime classpath: the IntelliJ Platform
    // jars carry service registrations whose classes reference
    // org.junit.runners.model.Statement, and without this the test executor
    // dies at startup with ClassNotFoundException before any test runs.
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Deliberately NOT `recommended()`, which picks IDEs near the BUILD
            // target (251.x). What actually needs proving is that the code still
            // runs on the OLDEST IDE we claim to support: sinceBuild is 241 but
            // we compile against 2025.1, so a 242+ API could sneak in and only
            // fail for users on 2024.1. Verifying against 241 is the guard.
            //
            // `create` pins exact releases rather than `select`, which would
            // drag in every patch release in the range.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.1")
            // Free extra check: the exact build installed on this machine.
            local("D:/install/IntelliJ IDEA 2025.1.1.1")
        }
    }

    // Signing is required if you want installers to see no "unverified" warning.
    // The JetBrains Marketplace has no key-registration page (the docs say that
    // is "not available yet"), so the author generates the key pair themselves
    // with openssl -- see README "签名与上传" for the exact commands.
    //
    // File-based, so no multi-kilobyte PEM blobs end up in the environment:
    //
    //   CERTIFICATE_CHAIN_FILE   path to chain.crt
    //   PRIVATE_KEY_FILE         path to private.pem
    //   PRIVATE_KEY_PASSWORD     key passphrase (may be empty)
    //
    // `signPlugin` is skipped when these are absent, so `buildPlugin` still
    // works on a machine with no keys at all.
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE").map { file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Upload token also comes from the environment:
    //
    //   PUBLISH_TOKEN             from https://plugins.jetbrains.com/author/me/tokens
    //
    // Run `./gradlew publishPlugin` once signing is configured.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Explicit: an omitted `hidden` would be passed through to the upload
        // API's `isHidden` flag, and a hidden plugin uploads successfully while
        // staying off the public listing entirely.
        hidden = false
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }

    wrapper {
        gradleVersion = "9.9.2"
    }
}
