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
            Signature[] signers=info.signingInfo==null?null:info.signingInfo.getApkContentsSigners();
            String cert="";
            if(signers!=null&&signers.length==1) {
                StringBuilder hex=new StringBuilder();
                for(byte b:MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()))
                    hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                cert=hex.toString();
            }
            return matches(Build.VERSION.SDK_INT,Build.FINGERPRINT,info.getLongVersionCode(),
                (receiver.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0,
                enabled,receiver.exported,receiver.permission,cert);
        }catch(Exception unavailable){return false;}
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
