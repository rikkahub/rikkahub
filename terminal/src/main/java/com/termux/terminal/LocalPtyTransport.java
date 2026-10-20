package com.termux.terminal;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A local subprocess coupled to a new pseudoterminal, see {@link JNI#createSubprocess}.
 */
public final class LocalPtyTransport implements TerminalTransport {

    private static final String LOG_TAG = "LocalPtyTransport";

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;

    /** The pid of the process. 0 if not started and -1 if finished running. */
    private int mPid;
    private boolean mKilled;

    /** The master half of the pseudoterminal pair, null until started. */
    private ParcelFileDescriptor mTerminalFileDescriptor;
    private InputStream mInputStream;
    private OutputStream mOutputStream;

    public LocalPtyTransport(String shellPath, String cwd, String[] args, String[] env) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
    }

    @Override
    public void start(int columns, int rows) throws IOException {
        int[] processId = new int[1];
        int terminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns);
        if (terminalFileDescriptor < 0) throw new IOException("Failed to start " + mShellPath);

        synchronized (this) {
            mTerminalFileDescriptor = ParcelFileDescriptor.adoptFd(terminalFileDescriptor);
            mPid = processId[0];
            // Streams on a borrowed FileDescriptor do not own it, the pty is closed through mTerminalFileDescriptor.
            mInputStream = new FileInputStream(mTerminalFileDescriptor.getFileDescriptor());
            mOutputStream = new FileOutputStream(mTerminalFileDescriptor.getFileDescriptor());
            if (mKilled) sendKill();
        }
    }

    @Override
    public synchronized InputStream getInputStream() {
        return mInputStream;
    }

    @Override
    public synchronized OutputStream getOutputStream() {
        return mOutputStream;
    }

    @Override
    public synchronized void setWindowSize(int columns, int rows) {
        if (mPid > 0) JNI.setPtyWindowSize(mTerminalFileDescriptor.getFd(), rows, columns);
    }

    @Override
    public int waitFor() {
        int pid;
        synchronized (this) {
            pid = mPid;
        }
        int exitStatus = JNI.waitFor(pid);
        synchronized (this) {
            // The pid may be reused once reaped, never signal it again.
            mPid = -1;
        }
        return exitStatus;
    }

    @Override
    public synchronized void kill() {
        mKilled = true;
        sendKill();
    }

    @Override
    public synchronized void close() {
        if (mTerminalFileDescriptor == null) return;
        try {
            mTerminalFileDescriptor.close();
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed closing pty: " + e.getMessage());
        }
    }

    private void sendKill() {
        if (mPid <= 0) return;
        try {
            Os.kill(mPid, OsConstants.SIGKILL);
        } catch (ErrnoException e) {
            Log.w(LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
        }
    }

}
