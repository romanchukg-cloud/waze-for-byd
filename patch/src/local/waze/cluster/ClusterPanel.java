package local.waze.cluster;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** The Instrument cluster button and settings panel, matching Google Maps for BYD. Changes apply live. */
public final class ClusterPanel {
    private static final String MAIN = "com.waze.MainActivity", TAG = "local.cluster.settings.button";
    private static SharedPreferences preferences;
    private ClusterPanel() { }

    public static synchronized void install(Application app) {
        if (preferences != null) return;
        preferences = ClusterSettings.prefs(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) {
                if (MAIN.equals(a.getClass().getName())) a.getWindow().getDecorView().post(() -> attach(a));
            }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }
    private static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    private static void attach(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        if (root.findViewWithTag(TAG) != null) return;
        Button button = new Button(activity);
        button.setTag(TAG);
        button.setText("Instrument cluster");
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xee303438);
        background.setCornerRadius(dp(activity, 24));
        button.setBackground(background);
        button.setElevation(dp(activity, 6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(activity, 44), Gravity.TOP | Gravity.RIGHT);
        int inset = root.getRootWindowInsets() == null ? dp(activity, 48) : root.getRootWindowInsets().getStableInsetTop();
        // Below Waze's own round buttons in the top-right corner.
        lp.topMargin = Math.max(inset, dp(activity, 48)) + dp(activity, 40);
        lp.rightMargin = dp(activity, 16);
        button.setOnClickListener(v -> show(activity));
        root.addView(button, lp);
    }
    private static TextView label(Context c, String text, int size) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(size);
        v.setPadding(0, dp(c, 10), 0, dp(c, 4));
        return v;
    }
    private static void toggle(LinearLayout panel, Context c, String title, String key, boolean value) {
        Switch control = new Switch(c);
        control.setText(title);
        control.setTextSize(18);
        control.setMinHeight(dp(c, 56));
        control.setChecked(value);
        control.setOnCheckedChangeListener((v, on) -> preferences.edit().putBoolean(key, on).apply());
        panel.addView(control);
    }
    private static void options(LinearLayout panel, Context c, String title, String[] labels, int selected, Selection selection) {
        panel.addView(label(c, title, 17));
        RadioGroup group = new RadioGroup(c);
        group.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(c);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTextSize(16);
            b.setTag(i);
            group.addView(b, new RadioGroup.LayoutParams(0, -2, 1f));
            if (i == selected) b.setChecked(true);
        }
        group.setOnCheckedChangeListener((g, id) -> { View v = g.findViewById(id); if (v != null) selection.select((Integer) v.getTag()); });
        panel.addView(group);
    }
    private interface Selection { void select(int index); }
    private static void slider(LinearLayout panel, Context c, String title, String key, int min, int max, int value) {
        TextView text = label(c, title + ": " + value + "%", 17);
        panel.addView(text);
        SeekBar seek = new SeekBar(c);
        seek.setMax(max - min);
        seek.setProgress(value - min);
        seek.setContentDescription(title);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean user) {
                int n = progress + min;
                text.setText(title + ": " + n + "%");
                if (user) preferences.edit().putInt(key, n).apply();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        panel.addView(seek, new LinearLayout.LayoutParams(-1, dp(c, 48)));
    }
    /** Restarts Waze; a guidance card left by the old process is cleared by the next launch. */
    static void restart(Activity a) {
        Intent launch = a.getPackageManager().getLaunchIntentForPackage(a.getPackageName());
        if (launch == null || launch.getComponent() == null) {
            Toast.makeText(a, "Close Waze completely and open it again.", Toast.LENGTH_LONG).show();
            return;
        }
        android.util.Log.i("WazeClusterTest", "restarting Waze from cluster settings");
        a.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        Runtime.getRuntime().exit(0);
    }
    static void show(Activity activity) {
        Context c = new ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(c, 24), dp(c, 8), dp(c, 24), dp(c, 12));
        ClusterSettings.State s = ClusterSettings.state(activity);
        toggle(panel, c, "Show map on instrument cluster", ClusterSettings.MAP_ENABLED, s.mapEnabled);
        toggle(panel, c, "Send turn guidance to cluster and HUD", ClusterSettings.VEHICLE_GUIDANCE, s.vehicleGuidance);
        panel.addView(label(c, ClusterStatus.hud() + " Use only one HUD navigation app at a time.", 14));
        if (ClusterStatus.untestedHudAvailable()) {
            Switch hud = new Switch(c);
            hud.setText("Try standalone HUD on this firmware (experimental)");
            hud.setTextSize(18);
            hud.setMinHeight(dp(c, 56));
            hud.setChecked(s.hudUntested);
            Button apply = new Button(c);
            apply.setText("Restart Waze to apply");
            apply.setAllCaps(false);
            apply.setOnClickListener(v -> restart(activity));
            apply.setVisibility(s.hudUntested != ClusterStatus.standaloneActive() ? View.VISIBLE : View.GONE);
            hud.setOnCheckedChangeListener((v, on) -> {
                preferences.edit().putBoolean(ClusterSettings.HUD_UNTESTED, on).commit();
                apply.setVisibility(on != ClusterStatus.standaloneActive() ? View.VISIBLE : View.GONE);
            });
            panel.addView(hud);
            panel.addView(label(c, "First turn off HUD output in OpenBYD or any other HUD app. Restart Waze to apply.", 14));
            panel.addView(apply);
        }
        final int[] themes = {ClusterSettings.THEME_AUTO, ClusterSettings.THEME_SYSTEM, ClusterSettings.THEME_LIGHT, ClusterSettings.THEME_DARK};
        int theme = 0;
        for (int i = 0; i < themes.length; i++) if (themes[i] == s.clusterTheme) theme = i;
        options(panel, c, "Cluster map colours", new String[]{"Auto", "Match screen", "Light", "Dark"}, theme,
                i -> preferences.edit().putInt(ClusterSettings.CLUSTER_THEME, themes[i]).apply());
        panel.addView(label(c, "Auto (default) is light between sunrise and sunset at the car's position. Match screen follows the head unit's theme where it reports one. "
                + "The centre screen keeps Waze's own colour setting. " + ClusterStatus.theme(), 14));
        slider(panel, c, "Pointer vertical offset (- up, + down)", ClusterSettings.POINTER_VERTICAL, -25, 25, s.pointerVertical);
        // Android 13 BYD builds (DiLink 5.1); also kept visible once the owner has chosen a profile.
        if (Build.VERSION.SDK_INT >= 33 || s.profile != DiLink51Layout.Profile.AUTOMATIC) {
            panel.addView(label(c, "DiLink 5.1 cluster layout", 18));
            options(panel, c, "Layout profile", new String[]{"Automatic", "DiLink 5.1", "DiLink 5"}, s.profile.ordinal(),
                    i -> preferences.edit().putString(ClusterSettings.PROFILE, DiLink51Layout.Profile.values()[i].name()).apply());
            options(panel, c, "Cluster theme", new String[]{"Scenario", "Map", "Simple", "Follow"}, s.mode.ordinal(),
                    i -> preferences.edit().putString(ClusterSettings.MODE, DiLink51Layout.Mode.values()[i].name()).apply());
            options(panel, c, "Map theme background", new String[]{"Theme default", "Light", "Dark"}, s.contrast.ordinal(),
                    i -> preferences.edit().putString(ClusterSettings.CONTRAST, DiLink51Layout.Contrast.values()[i].name()).apply());
            panel.addView(label(c, "Automatic uses the DiLink 5.1 layout only on verified firmware. Follow tracks the cluster's theme and needs Usage Access, "
                    + "granted once from a computer: adb shell appops set com.waze GET_USAGE_STATS allow", 14));
        }
        panel.addView(label(c, "Changes apply immediately and are saved automatically.", 14));
        Button support = new Button(c);
        support.setText("Support and diagnostics");
        support.setAllCaps(false);
        support.setOnClickListener(v -> {
            try { activity.startActivity(new Intent().setClassName(activity, "local.waze.diagnostics.SupportActivity")); }
            catch (RuntimeException e) { Toast.makeText(activity, "Support is unavailable.", Toast.LENGTH_LONG).show(); }
        });
        panel.addView(support);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(c).setTitle("Instrument cluster").setView(scroll).setPositiveButton("Done", null).create();
        dialog.show();
        dialog.getWindow().setLayout(Math.min(dp(c, 650), (int) (activity.getResources().getDisplayMetrics().widthPixels * .8f)), -2);
    }
}
