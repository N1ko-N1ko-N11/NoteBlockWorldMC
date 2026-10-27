package io.github.noteblockworldmc.util;

import java.util.concurrent.*;

/** Only nonblocking cancellations/socket closes run here; cancelled timers are removed immediately. */
public final class DeadlineTimer {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "NoteblockWorldMC-deadlines"); t.setDaemon(true); return t;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); TIMER.setKeepAliveTime(1, TimeUnit.SECONDS); TIMER.allowCoreThreadTimeOut(true); }
    private DeadlineTimer() {}
    public static ScheduledFuture<?> schedule(Runnable action, long millis) {
        return TIMER.schedule(action, Math.max(1, millis), TimeUnit.MILLISECONDS);
    }
}
