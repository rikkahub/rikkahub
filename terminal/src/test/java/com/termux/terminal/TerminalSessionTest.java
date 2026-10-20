package com.termux.terminal;

import android.os.Message;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;

/**
 * Input written from the main thread must never block on a queue nobody reads. Main thread messages are not delivered
 * on the JVM, so the tests hand them to the handler themselves.
 */
public class TerminalSessionTest {

    private final FakeTransport mTransport = new FakeTransport();
    private final TerminalSession mSession = new TerminalSession(mTransport, 100, new FakeClient());

    @After
    public void tearDown() {
        mSession.finishIfRunning();
        mSession.cleanupResources(0);
    }

    @Test(timeout = 5000)
    public void inputWhileStartingIsSentOnceStarted() throws Exception {
        mSession.initializeEmulator(80, 24);

        byte[] paste = new byte[8192];
        mSession.write(paste, 0, paste.length);
        byte[] typed = new byte[4096];
        Arrays.fill(typed, (byte) 'x');
        mSession.write(typed, 0, typed.length - 3);
        mSession.write(bytes("ls\r"), 0, 3);
        mSession.write(bytes("dropped"), 0, 7);

        startTransport();

        typed[typed.length - 3] = 'l';
        typed[typed.length - 2] = 's';
        typed[typed.length - 1] = '\r';
        mTransport.awaitReceived(typed.length);
        assertEquals(new String(typed, StandardCharsets.UTF_8), mTransport.received());
    }

    @Test(timeout = 5000)
    public void inputAfterTransportStopsTakingItDoesNotBlock() throws Exception {
        mTransport.mRejectInput = true;
        mSession.initializeEmulator(80, 24);
        startTransport();

        byte[] chunk = new byte[4096];
        for (int i = 0; i < 4; i++) {
            mSession.write(chunk, 0, chunk.length);
        }
    }

    private void startTransport() throws InterruptedException {
        mTransport.mStartReleased.countDown();
        mTransport.mStarted.await();
        Message message = new Message();
        message.what = TerminalSession.MSG_STARTED;
        mSession.mMainThreadHandler.handleMessage(message);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Starts once released, then records the input it is sent or rejects it. */
    private static final class FakeTransport implements TerminalTransport {
        final CountDownLatch mStartReleased = new CountDownLatch(1);
        final CountDownLatch mStarted = new CountDownLatch(1);
        final CountDownLatch mExited = new CountDownLatch(1);
        volatile boolean mRejectInput;
        private final ByteArrayOutputStream mReceived = new ByteArrayOutputStream();

        @Override
        public void start(int columns, int rows) throws IOException {
            try {
                mStartReleased.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            mStarted.countDown();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (mRejectInput) throw new IOException("Transport closed");
                    synchronized (mReceived) {
                        mReceived.write(b, off, len);
                        mReceived.notifyAll();
                    }
                }
            };
        }

        void awaitReceived(int count) throws InterruptedException {
            synchronized (mReceived) {
                while (mReceived.size() < count) mReceived.wait();
            }
        }

        String received() {
            synchronized (mReceived) {
                return mReceived.toString();
            }
        }

        @Override
        public void setWindowSize(int columns, int rows) {
        }

        @Override
        public int waitFor() {
            try {
                mExited.await();
            } catch (InterruptedException e) {
                // Ignore.
            }
            return 0;
        }

        @Override
        public void kill() {
            mExited.countDown();
        }

        @Override
        public void close() {
            mExited.countDown();
        }
    }

    private static final class FakeClient implements TerminalSessionClient {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
        }

        @Override
        public void logWarn(String tag, String message) {
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
        }
    }
}
