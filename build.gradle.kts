plugins {
    java
    distribution
    id("org.omegat.gradle") version "1.5.11"
}

repositories {
    mavenCentral()
    mavenLocal()
}

version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

omegat {
    version = "6.1.1"
    pluginClass = "io.github.ashadow22.omegat.gemini.GeminiTranslateAPI"
}

distributions {
    main {
        contents {
            from(tasks["jar"], "README.md", "COPYING")
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Name" to "Google Gemini API",
            "Plugin-Version" to project.version,
            "Plugin-Author" to "Oleg Hudiakov",
            "Plugin-Description" to "Google Gemini Machine Translation plugin for OmegaT with glossary and context support",
            "Plugin-Category" to "machinetranslator",
            "OmegaT-Plugins" to "io.github.ashadow22.omegat.gemini.GeminiTranslateAPI"
        )
    }
}
