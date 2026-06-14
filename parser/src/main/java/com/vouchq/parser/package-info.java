/**
 * <b>parser</b> — pure-Java library (AGPL-3.0, like the whole repo). Parses AI-agent capability definitions
 * (Claude Skills now; MCP {@code tools/list} later) into a normalized, hashable
 * {@link com.vouchq.parser.ParsedSkill}. The control plane depends on this
 * module for ingestion, pinning (박제), and drift detection. No framework deps.
 */
package com.vouchq.parser;
