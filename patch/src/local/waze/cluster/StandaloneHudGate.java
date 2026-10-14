package local.waze.cluster;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import java.security.MessageDigest;

/** Conservative opt-in to the one receiver/firmware combination reported in the handoff. */
public final class StandaloneHudGate {
    static final String PACKAGE="com.byd.clusterdebug", RECEIVER=PACKAGE+".BroadcastReceiverCAN";
    static final String FIRMWARE="BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys";
    static final String CERT="efe3ca8ada0d10c655c3df9910ad2ebc121a47d9a6358434eb24074309933efc";
    public static boolean eligible(Context c) {
        if(Build.VERSION.SDK_INT<28)return false;
        try {
            PackageManager pm=c.getPackageManager();
            ComponentName component=new ComponentName(PACKAGE,RECEIVER);
            ActivityInfo receiver=pm.getReceiverInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);
            PackageInfo info=pm.getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);
            boolean enabled=receiver.enabled&&receiver.applicationInfo.enabled&&enabled(pm.getComponentEnabledSetting(component))&&enabled(pm.getApplicationEnabledSetting(PACKAGE));
            return matches(Build.VERSION.SDK_INT,Build.FINGERPRINT,info.getLongVersionCode(),system(receiver),
                enabled,receiver.exported,receiver.permission,certificate(info));
        }catch(Exception unavailable){return false;}
    }
    /** Local Support text only, so an owner can show which check fails on another firmware. */
    public static String describe(Context c) {
        StringBuilder s=new StringBuilder("HUD 5.1 transport: ").append(eligible(c)?"active":"inactive, DiLink 5 AMap broadcast is used")
            .append("\nVerified DiLink 5.1 firmware: ").append(FIRMWARE.equals(Build.FINGERPRINT)?"yes":"no");
        try {
            PackageManager pm=c.getPackageManager();
            ComponentName component=new ComponentName(PACKAGE,RECEIVER);
            ActivityInfo receiver=pm.getReceiverInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);
            PackageInfo info=pm.getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);
            s.append("\nHUD receiver: version ").append(info.getLongVersionCode()).append(info.getLongVersionCode()==10601004?" (expected)":" (expected 10601004)")
                .append(", system ").append(system(receiver)?"yes":"no")
                .append(", enabled ").append(receiver.enabled&&receiver.applicationInfo.enabled&&enabled(pm.getComponentEnabledSetting(component))&&enabled(pm.getApplicationEnabledSetting(PACKAGE))?"yes":"no")
                .append(", exported ").append(receiver.exported?"yes":"no")
                .append(", permission ").append(receiver.permission==null||receiver.permission.isEmpty()?"none":"required")
                .append(", signer ").append(CERT.equals(certificate(info))?"matches":"differs");
        }catch(PackageManager.NameNotFoundException e){s.append("\nHUD receiver: not found");}
        catch(Exception e){s.append("\nHUD receiver: check failed (").append(e.getClass().getSimpleName()).append(")");}
        return s.toString();
    }
    private static boolean system(ActivityInfo receiver) {
        return (receiver.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
    }
    private static String certificate(PackageInfo info) throws Exception {
        Signature[] signers=info.signingInfo==null?null:info.signingInfo.getApkContentsSigners();
        if(signers==null||signers.length!=1)return "";
        StringBuilder hex=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()))
            hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return hex.toString();
    }
    private static boolean enabled(int state){return state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT||state==PackageManager.COMPONENT_ENABLED_STATE_ENABLED;}
    static boolean matches(int api,String firmware,long version,boolean system,boolean enabled,boolean exported,String permission,String cert) {
        return api>=28&&FIRMWARE.equals(firmware)&&version==10601004&&system&&enabled&&exported
            &&(permission==null||permission.isEmpty())&&CERT.equals(cert);
    }
    public static Intent intent(String packet) {
        return new Intent("byd.hud.NAVIGATION").setComponent(new ComponentName(PACKAGE,RECEIVER))
            .putExtra("normal",packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
    }
}
