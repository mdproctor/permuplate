plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.0"
}

group = "io.quarkiverse.permuplate"
version = "1.0.0-SNAPSHOT"

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
    // Algorithm library — bundled into plugin zip.
    // Requires: /opt/homebrew/bin/mvn -pl permuplate-ide-support package -am
    // Run from permuplate root before building this module.
    implementation(files("../permuplate-ide-support/target/quarkus-permuplate-ide-support-1.0.0-SNAPSHOT.jar"))

    intellijPlatform {
        intellijIdeaCommunity("2023.2")
        bundledPlugin("com.intellij.java")   // Java PSI APIs
        instrumentationTools()               // @NotNull/@Nullable parameter instrumentation
    }

    // Annotation types available in test fixtures
    testImplementation(files(
        "../permuplate-annotations/target/quarkus-permuplate-annotations-1.0.0-SNAPSHOT.jar"
    ))
}

intellijPlatform {
    pluginConfiguration {
        id = "io.quarkiverse.permuplate"
        name = "Permuplate"
        version = "1.0.0-SNAPSHOT"
        description = "IDE support for Permuplate annotation-driven code generation"
        ideaVersion {
            sinceBuild = "232"  // 2023.2
        }
    }
}
