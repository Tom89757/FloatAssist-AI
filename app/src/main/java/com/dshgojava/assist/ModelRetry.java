package com.dshgojava.assist;

/**
 * Optional capability of one run: repeat only its last model call after a model
 * failure. Captures and preflight are not repeated.
 */
public interface ModelRetry {
    boolean canRetryModel();

    void retryModel();
}
