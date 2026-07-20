package com.mg4.abrptelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Upload history and the service state derived from it. A single failed upload is normal
 * in a car (tunnel, dead spot); three in a row means something is actually wrong.
 */
public class UploadLogTest {

    private UploadLog log;

    @Before
    public void setUp() {
        log = new UploadLog();
    }

    private UploadLog.Entry ok() {
        return new UploadLog.Entry(1L, 200, true, "OK");
    }

    private UploadLog.Entry fail(int status, String detail) {
        return new UploadLog.Entry(1L, status, false, detail);
    }

    // ---- Ring buffer ----

    @Test
    public void keepsOnlyTheLastTwentyCalls() {
        for (int i = 0; i < 25; i++) {
            log.record(new UploadLog.Entry(i, 200, true, "call" + i));
        }
        List<UploadLog.Entry> recent = log.recent();
        assertEquals(UploadLog.MAX_ENTRIES, recent.size());
        // Most recent first.
        assertEquals("call24", recent.get(0).detail);
        assertEquals("call5", recent.get(recent.size() - 1).detail);
    }

    @Test
    public void anEmptyLogHasNoLatestEntry() {
        assertNull(log.latest());
        assertTrue(log.recent().isEmpty());
    }

    @Test
    public void recordsTheHttpStatusAndDetail() {
        log.record(fail(401, "HTTP 401"));
        UploadLog.Entry latest = log.latest();
        assertEquals(401, latest.httpStatus);
        assertEquals("HTTP 401", latest.detail);
        assertEquals(false, latest.success);
    }

    @Test
    public void aRequestThatNeverReachedAServerHasNoStatus() {
        // Status 0 is how the UI knows to show "---" rather than a bogus code.
        log.record(fail(0, "No internet"));
        assertEquals(0, log.latest().httpStatus);
    }

    // ---- Error state ----

    @Test
    public void stoppedWhenTheServiceIsNotRunning() {
        log.record(ok());
        assertEquals(UploadLog.State.STOPPED, log.state(false));
    }

    @Test
    public void startingWhileRunningWithNoAttemptYet() {
        assertEquals(UploadLog.State.STARTING, log.state(true));
    }

    @Test
    public void runningAfterASuccess() {
        log.record(ok());
        assertEquals(UploadLog.State.RUNNING, log.state(true));
    }

    @Test
    public void oneOrTwoFailuresAreNotAnError() {
        // A tunnel or a dead spot must not alarm the driver.
        log.record(fail(0, "No internet"));
        assertEquals(UploadLog.State.RUNNING, log.state(true));
        log.record(fail(0, "No internet"));
        assertEquals(UploadLog.State.RUNNING, log.state(true));
    }

    @Test
    public void threeConsecutiveFailuresAreAnError() {
        for (int i = 0; i < 3; i++) log.record(fail(500, "HTTP 500"));
        assertEquals(UploadLog.State.ERROR, log.state(true));
        assertEquals(3, log.consecutiveFailures());
    }

    @Test
    public void aSuccessClearsTheErrorState() {
        for (int i = 0; i < 5; i++) log.record(fail(500, "HTTP 500"));
        assertEquals(UploadLog.State.ERROR, log.state(true));

        log.record(ok());
        assertEquals(UploadLog.State.RUNNING, log.state(true));
        assertEquals(0, log.consecutiveFailures());
    }

    @Test
    public void failuresMustBeConsecutiveToCount() {
        log.record(fail(500, "a"));
        log.record(fail(500, "b"));
        log.record(ok());
        log.record(fail(500, "c"));
        // 2 failures, then a success, then 1: not three in a row.
        assertEquals(1, log.consecutiveFailures());
        assertEquals(UploadLog.State.RUNNING, log.state(true));
    }

    // ---- Threading ----

    @Test
    public void concurrentWritesAndReadsStayConsistent() throws Exception {
        // Written from the scheduler thread, read from the UI thread.
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        log.record(ok());
                        log.recent();
                        log.state(true);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(UploadLog.MAX_ENTRIES, log.recent().size());
    }
}
