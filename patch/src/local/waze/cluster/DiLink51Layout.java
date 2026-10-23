// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

/** DiLink 5.1 cluster geometry as measured by DiPlay's maintainer. Pure logic, no Android types. */
public final class DiLink51Layout {
    public static final String FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys";
    // DiPlay also enables this layout on a Leopard 5 Ultra build with the same three fission layers.
    public static final String LEOPARD5_FINGERPRINT = "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.021352:user/release-keys";
    public static final String FULL = "shared_fission_bg_XDJAScreenProjection_0", SIDE = "shared_fission_bg_XDJAScreenProjection_1";
    public static final int WIDTH = 1920, HEIGHT = 720;
    public enum Theme { SCENARIO, MAP, SIMPLE }
    /** Which cluster layout code runs. AUTOMATIC only selects DiLink 5.1 on a verified fingerprint. */
    public enum Profile { AUTOMATIC, DILINK51, DILINK5 }
    /** Fixed theme chosen by the owner, or FOLLOW the stock cluster activities through Usage Access. */
    public enum Mode { SCENARIO, MAP, SIMPLE, FOLLOW }
    public enum Contrast { DEFAULT, LIGHT, DARK }
    private DiLink51Layout() { }

    public static boolean verified(String fingerprint) {
        return FINGERPRINT.equals(fingerprint) || LEOPARD5_FINGERPRINT.equals(fingerprint);
    }
    public static boolean active(Profile profile, String fingerprint) {
        return profile == Profile.DILINK51 || profile == Profile.AUTOMATIC && verified(fingerprint);
    }
    /** Never substitute the full-map layer for a missing side layer: that would hide Scenario/Simple. */
    public static String displayName(Theme theme) { return theme == Theme.MAP ? FULL : SIDE; }
    /** Index of the only acceptable display, or -1. */
    public static int choose(String[] names, int[] widths, int[] heights, Theme theme) {
        if (theme == null) return -1;
        String wanted = displayName(theme);
        for (int i = 0; i < names.length; i++)
            if (wanted.equals(names[i])) return plan(widths[i], heights[i], theme) == null ? -1 : i;
        return -1;
    }
    public static Plan plan(int width, int height, Theme theme) {
        if (theme == null || width != WIDTH || height != HEIGHT) return null;
        // Map: keep speed/status above and gear/range below clear. Side themes: the right card.
        return theme == Theme.MAP ? new Plan(0, 144, WIDTH, 480, true) : new Plan(1320, 0, 600, HEIGHT, false);
    }
    public static boolean dark(Contrast contrast, Theme theme) {
        return contrast == Contrast.DARK || contrast != Contrast.LIGHT && theme == Theme.SIMPLE;
    }
    /** The theme to draw, or null to draw nothing. FOLLOW never guesses without a cluster signal. */
    public static Theme target(Mode mode, ClusterActivityState.Snapshot cluster, boolean access) {
        switch (mode) {
            case SCENARIO: return Theme.SCENARIO;
            case MAP: return Theme.MAP;
            case SIMPLE: return Theme.SIMPLE;
            default:
                if (!access || cluster == null) return null;
                if (cluster.theme == Theme.MAP) return Theme.MAP;
                if (cluster.miniMap) return cluster.theme == Theme.SIMPLE ? Theme.SIMPLE : Theme.SCENARIO;
                return null;
        }
    }

    /** Viewport inside the 1920x720 display and the map's visible area inside that surface. */
    public static final class Plan {
        // Map theme contrast bands, measured by DiPlay against the stock instrument readouts.
        public static final int TOP_FADE = 64, BOTTOM_FADE = 48, SIDE_PANEL = 460;
        public final int left, top, width, height;
        public final boolean fullMap;
        public final int visibleLeft, visibleTop, visibleRight, visibleBottom;
        Plan(int left, int top, int width, int height, boolean fullMap) {
            this.left = left; this.top = top; this.width = width; this.height = height; this.fullMap = fullMap;
            visibleLeft = fullMap ? SIDE_PANEL : 0;
            visibleTop = fullMap ? TOP_FADE : 0;
            visibleRight = fullMap ? width - SIDE_PANEL : width;
            visibleBottom = fullMap ? height - BOTTOM_FADE : height;
        }
        @Override public String toString() {
            return "viewport " + left + "," + top + " " + width + "x" + height + " visible "
                    + visibleLeft + "," + visibleTop + "-" + visibleRight + "," + visibleBottom;
        }
    }
}
