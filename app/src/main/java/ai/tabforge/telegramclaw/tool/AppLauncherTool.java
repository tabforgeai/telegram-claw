package ai.tabforge.telegramclaw.tool;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class AppLauncherTool {

    private final Context context;

    // Generic → Samsung fallbacks for apps where the LLM tends to guess stock package names.
    // Tried in order; first one that's installed wins.
    private static final Map<String, String[]> FALLBACKS = new LinkedHashMap<>();
    static {
        FALLBACKS.put("com.android.gallery3d",  new String[]{"com.sec.android.gallery3d", "com.google.android.apps.photos"});
        FALLBACKS.put("com.android.camera",     new String[]{"com.sec.android.app.camera", "com.android.camera2"});
        FALLBACKS.put("com.android.camera2",    new String[]{"com.sec.android.app.camera"});
        FALLBACKS.put("com.android.dialer",     new String[]{"com.samsung.android.dialer"});
        FALLBACKS.put("com.android.contacts",   new String[]{"com.samsung.android.contacts"});
        FALLBACKS.put("com.android.messaging",  new String[]{"com.samsung.android.messaging"});
        FALLBACKS.put("com.android.calendar",   new String[]{"com.samsung.android.calendar"});
        FALLBACKS.put("com.android.deskclock",  new String[]{"com.sec.android.app.clockpackage"});
        FALLBACKS.put("com.android.calculator", new String[]{"com.sec.android.app.popupcalculator"});
    }

    public AppLauncherTool(Context context) {
        this.context = context;
    }

    public String execute(String paramsJson) throws Exception {
        JSONObject params = new JSONObject(paramsJson);
        String packageName = params.getString("package_name");

        PackageManager pm = context.getPackageManager();
        String resolved = resolve(pm, packageName);

        if (resolved == null) {
            return "App not installed: " + packageName;
        }

        Intent launchIntent = pm.getLaunchIntentForPackage(resolved);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launchIntent);

        try {
            ApplicationInfo info = pm.getApplicationInfo(resolved, 0);
            String appName = pm.getApplicationLabel(info).toString();
            return "Launched " + appName + ".";
        } catch (PackageManager.NameNotFoundException e) {
            return "Launched " + resolved + ".";
        }
    }

    // Returns the first installable package: the requested one, or a fallback if known.
    private String resolve(PackageManager pm, String packageName) {
        if (pm.getLaunchIntentForPackage(packageName) != null) return packageName;
        String[] fallbacks = FALLBACKS.get(packageName);
        if (fallbacks != null) {
            for (String alt : fallbacks) {
                if (pm.getLaunchIntentForPackage(alt) != null) return alt;
            }
        }
        return null;
    }
}
