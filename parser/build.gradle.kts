// Pure-Java library (AGPL-3.0, like the whole repo) — no Spring, no framework
// lock-in — so it can be depended on by the control plane (and external consumers).
plugins {
    `java-library`
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")                       // SKILL.md frontmatter
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2") // canonical JSON for stable hashing

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
