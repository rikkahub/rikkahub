package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The process behind a {@link TerminalSession}, e.g. a local pty subprocess ({@link LocalPtyTransport}) or a shell on a
 * remote host.
 * <p>
 * Threading: {@link #start(int, int)} and {@link #waitFor()} are called on a background thread and may block. The
 * streams are used by dedicated reader and writer threads once started. {@link #setWindowSize(int, int)},
 * {@link #kill()} and {@link #close()} are called on the main thread and must not block.
 */
public interface TerminalTransport {

    /** Start the process with the initial window size. Called once; a thrown exception fails the session. */
    void start(int columns, int rows) throws IOException;

    /** Output of the process, read until end of stream. */
    InputStream getInputStream();

    /** Input of the process. */
    OutputStream getOutputStream();

    /** Inform the process of a new window size. Only called after a successful {@link #start(int, int)}. */
    void setWindowSize(int columns, int rows);

    /**
     * Block until the process has exited.
     *
     * @return the exit status of the process, or the negated signal that stopped it.
     */
    int waitFor();

    /** Forcefully end the process. May be called while {@link #start(int, int)} is still running. */
    void kill();

    /** Release resources after the process has exited or failed to start. */
    void close();

    /** A line printed in the terminal before starting, e.g. while connecting to a remote host, or null for none. */
    default String describeStart() {
        return null;
    }

    /** The line printed in the terminal once the process has exited with the status from {@link #waitFor()}. */
    default String describeExit(int exitStatus) {
        String description = "Process completed";
        if (exitStatus > 0) {
            // Non-zero process exit.
            description += " (code " + exitStatus + ")";
        } else if (exitStatus < 0) {
            // Negated signal.
            description += " (signal " + (-exitStatus) + ")";
        }
        return description + " - press Enter";
    }

}
