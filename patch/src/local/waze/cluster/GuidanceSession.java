// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

/** Single-threaded guidance ownership, keepalive and source expiry. */
public final class GuidanceSession {
    // Parked Waze emits unchanged route snapshots about once per minute.
    public static final long KEEPALIVE_MS = 5000, STALE_MS = 90000;
    public interface Output {
        void guidance(GuidanceFrame frame);
        void end();
    }
    public interface Store {
        boolean wasShown();
        boolean setShown(boolean shown);
    }
    private final long keepaliveMs;
    private final Output output;
    private final Store store;
    private boolean shown;
    private GuidanceFrame latest, sent;
    private long updatedAt, sentAt;

    public GuidanceSession(Output output, Store store) {
        this(output,store,KEEPALIVE_MS);
    }
    public GuidanceSession(Output output, Store store, long keepaliveMs) {
        if(keepaliveMs<250 || keepaliveMs>60000)throw new IllegalArgumentException("Invalid keepalive");
        this.keepaliveMs=keepaliveMs;
        this.output = output;
        this.store = store;
        shown = store.wasShown();
    }
    public void update(GuidanceFrame frame, long now) {
        if (frame == null) { end(); return; }
        latest = frame;
        updatedAt = now; // Only genuine source callbacks may refresh this clock.
        tick(now);
    }
    public void tick(long now) {
        if (latest != null && now - updatedAt >= STALE_MS) latest = null;
        if (latest == null) { end(); return; }
        if (!latest.equals(sent) || now - sentAt >= keepaliveMs) {
            // Persist before sending, so even a process death between the two
            // operations leaves enough information for startup recovery.
            if (!shown) {
                if (!store.setShown(true)) throw new IllegalStateException("Cannot persist guidance ownership");
                shown = true;
            }
            output.guidance(latest);
            sent = latest;
            sentAt = now;
        }
    }
    public void end() {
        latest = null;
        sent = null;
        if (!shown) return; // Do not clear another navigation app's card when idle.
        output.end();
        if (!store.setShown(false)) throw new IllegalStateException("Cannot clear guidance ownership");
        shown = false; // Failed sends/writes keep ownership and are retried.
    }
}
