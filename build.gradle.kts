// Root build: shared configuration applied to every subproject once it adds the
// Java plugin. Keeps per-module build files focused on dependencies.
subprojects {
    group = "com.vouchq"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
