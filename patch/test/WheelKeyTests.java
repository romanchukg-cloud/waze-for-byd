// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import local.waze.cluster.WheelKeyPolicy.Binding;
import local.waze.cluster.WheelKeyPolicy.Bindings;
import local.waze.cluster.WheelKeyPolicy.Event;
import local.waze.cluster.WheelKeyPolicy.Learning;
import local.waze.cluster.WheelKeyPolicy.Result;

public final class WheelKeyTests {
    static int count;
    static void check(boolean ok, String name) { count++; if (!ok) throw new AssertionError(name); }
    static final int MODE = 305, IN = 291, OUT = 292;
    static long clock = 1000;
    static Event key(int action, int code, long down) { return new Event(action, code, code == MODE ? 300 : code == IN ? 115 : code == OUT ? 114 : 1, 7, "simulate-keys", down, 0, false); }
    static Result press(WheelKeyPolicy p, int code, boolean enabled, boolean map, boolean audio) {
        long down = ++clock;
        Result r = p.handle(key(WheelKeyPolicy.DOWN, code, down), enabled, map, audio);
        Result up = p.handle(key(WheelKeyPolicy.UP, code, down), enabled, map, audio);
        check(r.consume == up.consume, "release follows its press");
        return r;
    }
    public static void main(String[] args) {
        WheelKeyPolicy p = new WheelKeyPolicy(Bindings.tang());
        check(!press(p, IN, true, true, true).consume, "volume keeps its action outside zoom mode");
        Result on = press(p, MODE, true, true, true);
        check(on.consume && on.mode && p.mode(), "custom button turns zoom mode on");
        Result in = press(p, IN, true, true, true);
        check(in.consume && in.delta == 1, "volume up zooms in");
        Result out = press(p, OUT, true, true, true);
        check(out.consume && out.delta == -1, "volume down zooms out");
        check(!press(p, 24, true, true, true).consume, "other keys pass in zoom mode");
        Result off = press(p, MODE, true, true, true);
        check(off.consume && !off.mode, "custom button turns zoom mode off");
        check(!press(p, OUT, true, true, true).consume, "volume restored after zoom mode");

        press(p, MODE, true, true, true);
        check(!press(p, IN, true, true, false).consume && !p.mode(), "a call ends zoom mode and keeps volume");
        press(p, MODE, true, true, true);
        check(!press(p, IN, true, false, true).consume && !p.mode(), "no cluster map ends zoom mode");
        check(!press(p, MODE, false, true, true).consume, "off in settings: custom button keeps its action");
        check(!press(p, MODE, true, false, true).consume, "no cluster map: custom button keeps its action");

        // A consumed press stays consumed until release, even if the map disappears midway.
        press(p, MODE, true, true, true);
        long down = ++clock;
        check(p.handle(key(WheelKeyPolicy.DOWN, IN, down), true, true, true).delta == 1, "press zooms");
        Event repeat = new Event(WheelKeyPolicy.DOWN, IN, 115, 7, "simulate-keys", down, 1, false);
        Result held = p.handle(repeat, true, true, true);
        check(held.consume && held.delta == 0, "a held button zooms once");
        check(p.handle(key(WheelKeyPolicy.UP, IN, down), true, false, true).consume, "release stays consumed when the map is gone");
        check(!p.mode(), "mode ended with the map");

        Event other = new Event(WheelKeyPolicy.DOWN, MODE, 300, 7, "another-device", ++clock, 0, false);
        check(!p.handle(other, true, true, true).consume, "same code from another device does not match");

        Bindings b = Bindings.tang();
        Bindings swapped = WheelKeyPolicy.assign(b, 2, b.out);
        check(swapped.in.same(b.out) && swapped.out.same(b.in), "assigning a used button swaps the roles");
        boolean rejected = false;
        try { new Bindings(b.in, b.in, b.out); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "duplicate bindings rejected");

        Learning l = new Learning();
        check(!l.handle(key(WheelKeyPolicy.DOWN, 90, 1), 100).consume, "no assignment running: key passes");
        l.start(1, 100);
        check(l.role(100) == 1 && l.remaining(100) == Learning.TIMEOUT, "assignment waits ten seconds");
        check(!l.handle(new Event(WheelKeyPolicy.DOWN, 3, 3, 7, "simulate-keys", 2, 0, false), 200).consume, "home is never taken");
        Learning.Capture c = l.handle(key(WheelKeyPolicy.DOWN, 90, 3), 300);
        check(c.consume && c.role == 1 && c.binding.code == 90, "next press is assigned");
        check(l.handle(key(WheelKeyPolicy.UP, 90, 3), 301).consume, "its release is consumed");
        check(l.role(302) == 0, "one press ends the assignment");
        l.start(2, 1000);
        check(l.role(1000 + Learning.TIMEOUT) == 0, "assignment times out");

        WheelKeyPolicy.Recent recent = new WheelKeyPolicy.Recent();
        check(recent.describe(java.util.TimeZone.getTimeZone("UTC")).startsWith("No keys"), "empty diagnostic");
        recent.add(0, key(WheelKeyPolicy.DOWN, MODE, 1), "service", true);
        check(recent.describe(java.util.TimeZone.getTimeZone("UTC")).equals("00:00:00  key 305/300  simulate-keys  service, used"), "diagnostic line");
        System.out.println("WheelKeyTests: " + count + " checks passed");
    }
}
