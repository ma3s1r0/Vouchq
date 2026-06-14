package com.vouchq.ingestion;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Clones a Git repository into a fresh temporary working tree using JGit.
 *
 * <p>The optional token is held only in memory for the duration of the clone
 * (passed to {@link UsernamePasswordCredentialsProvider}); it is never logged or
 * persisted (기획서 §10).
 */
@Component
public class GitFetcher {

    /** A checked-out repository on a local path, deletable when done. */
    public interface Checkout extends AutoCloseable {
        Path path();

        @Override
        void close();
    }

    /**
     * Shallow-clone {@code repoUrl} into a new temp dir. Supports {@code file://},
     * {@code https://}, etc. A non-blank {@code token} is used as the HTTP
     * password (username "x-access-token", the GitHub/GitLab PAT convention).
     */
    public Checkout cloneRepository(String repoUrl, String token) {
        Path workDir;
        try {
            workDir = Files.createTempDirectory("vouchq-ingest-");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create temp dir for clone", e);
        }

        var clone = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(workDir.toFile())
                .setDepth(1);
        if (token != null && !token.isBlank()) {
            clone.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("x-access-token", token));
        }

        try (Git git = clone.call()) {
            // Clone done; close the Git handle but keep the working tree.
        } catch (Exception e) {
            deleteQuietly(workDir);
            // Deliberately omit the message detail's token; repoUrl is the source URI.
            throw new GitFetchException("Failed to clone " + repoUrl, e);
        }

        return new Checkout() {
            @Override
            public Path path() {
                return workDir;
            }

            @Override
            public void close() {
                deleteQuietly(workDir);
            }
        };
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp dir
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /** Thrown when a clone/fetch fails. Message never contains the token. */
    public static class GitFetchException extends RuntimeException {
        public GitFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
