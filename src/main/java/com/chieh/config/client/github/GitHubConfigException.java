package com.chieh.config.client.github;

/**
 * Raised when configuration cannot be fetched from or parsed out of GitHub.
 */
public class GitHubConfigException extends RuntimeException {

    public GitHubConfigException(String message) {
        super(message);
    }

    public GitHubConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
