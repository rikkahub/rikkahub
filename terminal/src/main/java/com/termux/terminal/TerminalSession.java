package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The process is provided by a {@link TerminalTransport}. When the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation will begin and threads will be spawned to start the process and
 * handle its I/O. All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 * <p>
 * Modified by RikkaHub: the local pty subprocess was moved into {@link LocalPtyTransport} so that a session can also
 * run on a remote shell, and the process is started on a background thread.
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    static final int MSG_STARTED = 2;
    private static final int MSG_START_FAILED = 3;
    private static final int MSG_PROCESS_EXITED = 4;

    /** How long to wait for output written right before the process exited. */
    private static final long OUTPUT_DRAIN_TIMEOUT_MILLIS = 1000;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the process.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** Whether the process has finished running or failed to start. */
    private boolean mFinished;

    /** The exit status of the process. Only valid if {@link #mFinished}. */
    private int mExitStatus;

    private final TerminalTransport mTransport;

    /** Whether {@link #mTransport} has started. Only accessed on the main thread. */
    private boolean mTransportStarted;

    /**
     * Input written while {@link #mTransport} is starting, moved to {@link #mTerminalToProcessIOQueue} once it has started.
     * Nothing reads that queue before then, so writing there could block the main thread on a full queue. As large as the
     * queue, so moving it never blocks. Only accessed on the main thread.
     */
    private final byte[] mPendingInput = new byte[4096];
    private int mPendingInputLength;

    /** The window size {@link #mTransport} was last told about. Only accessed on the main thread. */
    private int mTransportColumns, mTransportRows;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    /** Create a session running a local subprocess, see {@link LocalPtyTransport}. */
    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this(new LocalPtyTransport(shellPath, cwd, args, env), transcriptRows, client);
    }

    public TerminalSession(TerminalTransport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the process of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            mEmulator.resize(columns, rows);
            updateTransportSize();
        }
    }

    /** Tell a started transport about the emulator size, skipping unchanged sizes since that may cost a round trip. */
    private void updateTransportSize() {
        if (!mTransportStarted || !isRunning()) return;
        int columns = mEmulator.mColumns;
        int rows = mEmulator.mRows;
        if (columns == mTransportColumns && rows == mTransportRows) return;
        mTransportColumns = columns;
        mTransportRows = rows;
        mTransport.setWindowSize(columns, rows);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
        mTransportColumns = columns;
        mTransportRows = rows;

        String startDescription = mTransport.describeStart();
        if (startDescription != null) {
            byte[] bytesToWrite = ("[" + startDescription + "]\r\n").getBytes(StandardCharsets.UTF_8);
            mEmulator.append(bytesToWrite, bytesToWrite.length);
            notifyScreenUpdate();
        }

        new Thread("TermSessionRunner[" + mHandle + "]") {
            @Override
            public void run() {
                try {
                    mTransport.start(columns, rows);
                } catch (Exception e) {
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_START_FAILED, e));
                    return;
                }
                mMainThreadHandler.sendEmptyMessage(MSG_STARTED);

                Thread reader = new Thread("TermSessionInputReader[" + mHandle + "]") {
                    @Override
                    public void run() {
                        try {
                            final InputStream termIn = mTransport.getInputStream();
                            final byte[] buffer = new byte[4096];
                            while (true) {
                                int read = termIn.read(buffer);
                                if (read == -1) return;
                                if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                                mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                            }
                        } catch (Exception e) {
                            // Ignore, just shutting down.
                        }
                    }
                };
                reader.start();

                new Thread("TermSessionOutputWriter[" + mHandle + "]") {
                    @Override
                    public void run() {
                        final byte[] buffer = new byte[4096];
                        try {
                            final OutputStream termOut = mTransport.getOutputStream();
                            while (true) {
                                int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                                if (bytesToWrite == -1) return;
                                termOut.write(buffer, 0, bytesToWrite);
                                termOut.flush();
                            }
                        } catch (IOException e) {
                            // Ignore.
                        } finally {
                            // The transport takes no more input, but the session is only cleaned up once the main thread
                            // handles the exit: keep discarding until then, so writes from the main thread cannot block
                            // on a full queue nobody reads.
                            while (mTerminalToProcessIOQueue.read(buffer, true) != -1) {
                                // Discard.
                            }
                        }
                    }
                }.start();

                int exitStatus = mTransport.waitFor();
                try {
                    // Let the reader pick up what the process wrote right before exiting.
                    reader.join(OUTPUT_DRAIN_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                    // Ignore.
                }
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, exitStatus));
            }
        }.start();
    }

    /**
     * Write data to the process. Data written while the process is starting is sent once it has started, up to
     * {@link #mPendingInput} in total: a write that does not fit is dropped rather than blocking the main thread.
     */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mEmulator == null || !isRunning()) return;
        if (mTransportStarted) {
            mTerminalToProcessIOQueue.write(data, offset, count);
        } else if (count <= mPendingInput.length - mPendingInputLength) {
            System.arraycopy(data, offset, mPendingInput, mPendingInputLength, count);
            mPendingInputLength += count;
        }
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by forcefully ending the process. */
    public void finishIfRunning() {
        if (isRunning()) mTransport.kill();
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mFinished = true;
            mExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        mTransport.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return !mFinished;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_STARTED) {
                mTransportStarted = true;
                // The queue is still empty, so the pending input fits without blocking.
                if (mPendingInputLength > 0) {
                    mTerminalToProcessIOQueue.write(mPendingInput, 0, mPendingInputLength);
                    mPendingInputLength = 0;
                }
                // The view may have been resized while the process was starting.
                updateTransportSize();
            } else if (msg.what == MSG_START_FAILED) {
                Exception error = (Exception) msg.obj;
                String reason = error.getMessage() != null ? error.getMessage() : error.toString();
                finish(-1, "Failed to start: " + reason);
            } else if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                finish(exitCode, mTransport.describeExit(exitCode));
            }
        }

        private void finish(int exitStatus, String description) {
            cleanupResources(exitStatus);

            byte[] bytesToWrite = ("\r\n[" + description + "]").getBytes(StandardCharsets.UTF_8);
            mEmulator.append(bytesToWrite, bytesToWrite.length);
            notifyScreenUpdate();

            mClient.onSessionFinished(TerminalSession.this);
        }

    }

}
