rootProject.name = "vouchq"

include(":app")      // control plane (Spring Boot)
include(":parser")   // library — Skill/MCP definition parser
include(":scanner")  // library — rule-based risk scanner
// Whole repo is AGPL-3.0-or-later. Terms + rationale: see LICENSING.md
