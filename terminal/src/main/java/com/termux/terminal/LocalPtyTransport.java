package com.termux.terminal;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * A local subprocess coupled to a new pseudoterminal, see {@link JNI#createSubprocess}.
 */
public final class LocalPtyTransport implements TerminalTransport {

    private static final String LOG_TAG = "LocalPtyTransport";

    /** Rounds of killing session members, to also catch processes forked while the previous round ran. */
    private static final int KILL_ROUNDS = 3;

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

    /**
     * SIGKILL every process in the session of the subprocess, which is the session leader. Killing only the leader is
     * not enough: when it is PRoot, the shell and its jobs are tracees that keep running once their tracer dies.
     * Processes that started a session of their own (e.g. with setsid) are not affected.
     */
    private void sendKill() {
        if (mPid <= 0) return;
        for (int round = 0; round < KILL_ROUNDS; round++) {
            List<Integer> members = liveSessionMembers(mPid);
            if (members.isEmpty()) return;
            for (int pid : members) {
                try {
                    Os.kill(pid, OsConstants.SIGKILL);
                } catch (ErrnoException e) {
                    // ESRCH: exited in the meantime.
                    if (e.errno != OsConstants.ESRCH) Log.w(LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
                }
            }
        }
    }

    /** Pids of processes in session {@code sessionId} that have not exited yet, from /proc. */
    private static List<Integer> liveSessionMembers(int sessionId) {
        List<Integer> members = new ArrayList<>();
        String[] entries = new File("/proc").list();
        if (entries == null) return members;
        for (String entry : entries) {
            if (entry.isEmpty() || !Character.isDigit(entry.charAt(0))) continue;
            String stat;
            try {
                byte[] bytes = Files.readAllBytes(new File("/proc/" + entry + "/stat").toPath());
                stat = new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException | SecurityException e) {
                // Exited meanwhile, or not ours to see.
                continue;
            }
            if (isLiveSessionMember(stat, sessionId)) members.add(Integer.parseInt(entry));
        }
        return members;
    }

    /**
     * Whether a /proc/[pid]/stat line belongs to a process in {@code sessionId} that is not a zombie. The command name in
     * parentheses may itself contain spaces and parentheses, so fields are counted from the last ')'.
     */
    static boolean isLiveSessionMember(String stat, int sessionId) {
        int commandEnd = stat.lastIndexOf(')');
        if (commandEnd < 0) return false;
        // state ppid pgrp session ...
        String[] fields = stat.substring(commandEnd + 1).trim().split(" ");
        if (fields.length < 4) return false;
        try {
            return Integer.parseInt(fields[3]) == sessionId && !fields[0].equals("Z");
        } catch (NumberFormatException e) {
            return false;
        }
    }

}
