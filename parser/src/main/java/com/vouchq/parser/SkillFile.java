package com.vouchq.parser;

/**
 * One file inside a Skill, with its UTF-8 content and content hash.
 *
 * @param path   path relative to the skill directory, using '/' separators
 * @param sha256 hex SHA-256 of the file's raw bytes
 * @param content the file content decoded as UTF-8 (input for the scanner)
 */
public record SkillFile(String path, String sha256, String content) {}
