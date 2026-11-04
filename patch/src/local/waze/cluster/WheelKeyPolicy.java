// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelKeyPolicy in Maps Automotive for BYD (GPL-3.0-only).
package local.waze.cluster;

import java.util.HashMap;
import java.util.Map;

/** Pure policy for steering-wheel zoom: no Android objects, clocks, I/O or key injection. */
public final class WheelKeyPolicy {
    public static final int DOWN = 0, UP = 1;
    /** A physical key, identified the same way across restarts; device IDs are transient and not kept. */
    public static final class Binding {
        public final int code, scan;
        public final String device;
        public Binding(int code, int scan, String device) {
            if (code <= 0 || code > 65535 || scan < 0 || scan > 65535 || device == null || device.isEmpty() || device.length() > 128)
                throw new IllegalArgumentException("Invalid wheel binding");
            for (int i = 0; i < device.length(); i++) if (Character.isISOControl(device.charAt(i))) throw new IllegalArgumentException("Invalid wheel device");
            this.code = code;
            this.scan = scan;
            this.device = device;
        }
        boolean matches(Event event) { return code == event.code && scan == event.scan && device.equals(event.device); }
        boolean same(Binding other) { return code == other.code && scan == other.scan && device.equals(other.device); }
        @Override public String toString() { return "key " + code + "/" + scan + ", " + device; }
    }
    public static final class Bindings {
        public final Binding mode, in, out;
        public Bindings(Binding mode, Binding in, Binding out) {
            if (mode == null || in == null || out == null || mode.same(in) || mode.same(out) || in.same(out))
                throw new IllegalArgumentException("Conflicting wheel bindings");
            this.mode = mode;
            this.in = in;
            this.out = out;
        }
        /** Measured on a BYD Tang with DiLink 5.0. */
        public static Bindings tang() {
            return new Bindings(new Binding(305, 300, "simulate-keys"), new Binding(291, 115, "simulate-keys"), new Binding(292, 114, "simulate-keys"));
        }
        int role(Event event) { return mode.matches(event) ? 1 : in.matches(event) ? 2 : out.matches(event) ? 3 : 0; }
    }
    public static final class Event {
        public final int action, code, scan, deviceId, repeat;
        public final String device;
        public final long downTime;
        public final boolean canceled;
        public Event(int action, int code, int scan, int deviceId, String device, long downTime, int repeat, boolean canceled) {
            this.action = action;
            this.code = code;
            this.scan = scan;
            this.deviceId = deviceId;
            this.device = device;
            this.downTime = downTime;
            this.repeat = repeat;
            this.canceled = canceled;
        }
        String identity() { return deviceId + ":" + code + ":" + scan + ":" + downTime; }
    }
    public static final class Result {
        public final boolean consume, mode;
        public final int delta;
        Result(boolean consume, int delta, boolean mode) {
            this.consume = consume;
            this.delta = delta;
            this.mode = mode;
        }
    }
    private Bindings bindings;
    private final Map<String, Integer> owned = new HashMap<>();
    private boolean mode;
    public WheelKeyPolicy(Bindings bindings) {
        if (bindings == null) throw new IllegalArgumentException();
        this.bindings = bindings;
    }
    public void updateBindings(Bindings next) {
        if (next == null) throw new IllegalArgumentException();
        bindings = next;
        mode = false;
    }
    public void reset() {
        mode = false;
        owned.clear();
    }
    public boolean mode() { return mode; }
    public Result handle(Event event, boolean enabled, boolean mapReady, boolean audioSafe) {
        if (!enabled || !mapReady || !audioSafe) mode = false;
        String id = event.identity();
        // A consumed press stays consumed until its release, even if the map or a call changes midway.
        if (owned.containsKey(id)) {
            if (event.action == UP) owned.remove(id);
            return new Result(true, 0, mode);
        }
        if (event.action != DOWN || event.repeat != 0 || event.canceled || !enabled || !mapReady || !audioSafe)
            return new Result(false, 0, mode);
        int role = bindings.role(event);
        if (role == 0 || owned.size() >= 32) return new Result(false, 0, mode);
        if (role == 1) {
            owned.put(id, role);
            mode = !mode;
            return new Result(true, 0, mode);
        }
        if (!mode) return new Result(false, 0, false);
        owned.put(id, role);
        return new Result(true, role == 2 ? 1 : -1, true);
    }
    /** A button already used by another role swaps with it, so the three bindings stay distinct. */
    public static Bindings assign(Bindings current, int role, Binding learned) {
        if (current == null || learned == null || role < 1 || role > 3) throw new IllegalArgumentException("Invalid wheel assignment");
        Binding mode = current.mode, in = current.in, out = current.out, old = role == 1 ? mode : role == 2 ? in : out;
        if (mode.same(learned)) mode = old;
        if (in.same(learned)) in = old;
        if (out.same(learned)) out = old;
        if (role == 1) mode = learned;
        else if (role == 2) in = learned;
        else out = learned;
        return new Bindings(mode, in, out);
    }

    /** Press-to-assign for other wheels. The press and its release are consumed; home, back, power and recents are never taken. */
    public static final class Learning {
        public static final long TIMEOUT = 10000;
        public static final class Capture {
            static final Capture PASS = new Capture(false, 0, null), CONSUME = new Capture(true, 0, null);
            public final boolean consume;
            public final int role;
            public final Binding binding;
            Capture(boolean consume, int role, Binding binding) {
                this.consume = consume;
                this.role = role;
                this.binding = binding;
            }
        }
        private int role;
        private long until;
        private final Map<String, Boolean> taken = new HashMap<>();
        public synchronized void start(int role, long now) {
            if (role < 1 || role > 3 || now < 0) throw new IllegalArgumentException("Unknown wheel role");
            this.role = role;
            until = now + TIMEOUT;
        }
        public synchronized void cancel() {
            role = 0;
            until = 0;
        }
        public synchronized int role(long now) {
            if (role != 0 && (now < 0 || now >= until)) cancel();
            return role;
        }
        public synchronized long remaining(long now) { return role(now) == 0 ? 0 : until - now; }
        public synchronized Capture handle(Event event, long now) {
            String id = event.identity();
            if (taken.containsKey(id)) {
                if (event.action == UP) taken.remove(id);
                return Capture.CONSUME;
            }
            int active = role(now);
            if (active == 0 || event.action != DOWN || event.repeat != 0 || event.canceled || reserved(event.code)) return Capture.PASS;
            Binding binding;
            try { binding = new Binding(event.code, event.scan, event.device); }
            catch (IllegalArgumentException unnamed) { return Capture.PASS; }
            if (taken.size() >= 32) taken.clear();
            taken.put(id, Boolean.TRUE);
            cancel();
            return new Capture(true, active, binding);
        }
        static boolean reserved(int code) { return code == 3 || code == 4 || code == 26 || code == 187; }
    }

    /** Recent presses for the settings diagnostic, newest first. Only codes and the device name. */
    public static final class Recent {
        public static final int SIZE = 8;
        private final long[] at = new long[SIZE];
        private final int[] code = new int[SIZE], scan = new int[SIZE];
        private final String[] device = new String[SIZE], source = new String[SIZE];
        private final boolean[] kept = new boolean[SIZE];
        private int next, count;
        public synchronized void add(long wall, Event event, String from, boolean consumed) {
            if (event.action != DOWN || event.repeat != 0) return;
            String name = event.device == null ? "unknown device" : event.device.replaceAll("\\p{Cntrl}", "?");
            at[next] = wall;
            code[next] = event.code;
            scan[next] = event.scan;
            device[next] = name.length() > 40 ? name.substring(0, 40) : name;
            source[next] = from;
            kept[next] = consumed;
            next = (next + 1) % SIZE;
            if (count < SIZE) count++;
        }
        public synchronized String describe(java.util.TimeZone zone) {
            if (count == 0) return "No keys received yet. Press a steering-wheel button.";
            java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
            time.setTimeZone(zone);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < count; i++) {
                int k = (next - 1 - i + SIZE) % SIZE;
                if (i > 0) out.append('\n');
                out.append(time.format(new java.util.Date(at[k]))).append("  key ").append(code[k]).append('/').append(scan[k]).append("  ")
                        .append(device[k]).append("  ").append(source[k]).append(kept[k] ? ", used" : ", passed");
            }
            return out.toString();
        }
    }
}
