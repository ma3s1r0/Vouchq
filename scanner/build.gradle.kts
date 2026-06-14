// Pure-Java rule-based scanner (AGPL-3.0, like the whole repo). Depends on :parser
// to consume parsed Skill definitions; no framework deps.
plugins {
    `java-library`
}

dependencies {
    api(project(":parser"))   // ParsedSkill / SkillFile are part of the scanner's API surface

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
