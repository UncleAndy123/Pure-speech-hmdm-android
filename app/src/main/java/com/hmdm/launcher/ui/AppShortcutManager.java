package com.hmdm.launcher.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.util.AppInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



/*
 * CHANGES TO AppShortcutManager.java
 * ====================================
 * Only one method changes: getInstalledApps()
 *
 * After the existing server-whitelist filtering in getConfiguredApps(),
 * we apply a second filter: packages the user has hidden via
 * HomeCustomizationActivity are removed from the returned list.
 *
 * Security properties preserved:
 *   1. getConfiguredApps() still only adds apps from the MDM server config
 *      with showIcon=true and remove=false — no change there.
 *   2. The hidden set is a SUBSET of what the server allows — a user can
 *      only hide apps the server permitted, never surface new ones.
 *   3. If the MDM config is refreshed and an app is removed server-side,
 *      it disappears from getConfiguredApps() first, before the hidden
 *      filter even runs — no leakage possible.
 *
 * ====================================
 * FIND this method in AppShortcutManager.java:
 *
 *     public List<AppInfo> getInstalledApps(Context context, boolean bottom) {
 *
 * REPLACE the entire method body with the version below.
 * Everything else in the file stays the same.
 * ====================================
 */

public class AppShortcutManager {

    private static AppShortcutManager instance;

    public static AppShortcutManager getInstance() {
        if (instance == null) {
            instance = new AppShortcutManager();
        }
        return instance;
    }

    public int getInstalledAppCount(Context context, boolean bottom) {
        Map<String, Application> requiredPackages = new HashMap();
        Map<String, Application> requiredLinks = new HashMap();
        getConfiguredApps(context, bottom, requiredPackages, requiredLinks);
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packs = pm.getInstalledApplications(0);
        if (packs == null) {
            return requiredLinks.size();
        }
        // Calculate applications
        int packageCount = 0;
        for(int i = 0; i < packs.size(); i++) {
            ApplicationInfo p = packs.get(i);
            if (pm.getLaunchIntentForPackage(p.packageName) != null &&
                    requiredPackages.containsKey(p.packageName)) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setPackage(p.packageName);
                List<ResolveInfo> shortcuts = pm.queryIntentActivities(intent, 0);
                packageCount += shortcuts.size();
            }
        }
        return requiredLinks.size() + packageCount;
    }

    public List<AppInfo> getInstalledApps(Context context, boolean bottom) {
        Map<String, Application> requiredPackages = new LinkedHashMap<>();
        Map<String, Application> requiredLinks    = new LinkedHashMap<>();

        getConfiguredApps(context, bottom, requiredPackages, requiredLinks);

        // ---- Load user-hidden packages (subset of server-allowed apps) ----
        Set<String> hiddenPackages = HomeCustomizationActivity.loadHiddenPackages(context);

        List<AppInfo> appInfos = new ArrayList<>();
        PackageManager packageManager = context.getPackageManager();

        for (Map.Entry<String, Application> entry : requiredPackages.entrySet()) {
            String pkg = entry.getKey();

            // Skip packages the user has chosen to hide
            if (hiddenPackages.contains(pkg)) continue;

            try {
                packageManager.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // Not installed yet — skip
            }

            AppInfo newInfo  = new AppInfo();
            newInfo.type     = AppInfo.TYPE_APP;
            newInfo.packageName  = pkg;
            newInfo.keyCode      = entry.getValue().getKeyCode();
            newInfo.name         = entry.getValue().getName();
            newInfo.iconUrl      = entry.getValue().getIcon();
            newInfo.screenOrder  = entry.getValue().getScreenOrder();
            newInfo.useKiosk     = entry.getValue().isUseKiosk() ? 1 : 0;
            appInfos.add(newInfo);
        }

        for (Map.Entry<String, Application> entry : requiredLinks.entrySet()) {
            AppInfo newInfo  = new AppInfo();
            newInfo.type     = entry.getValue().getType() != null &&
                    entry.getValue().getType().equals(Application.TYPE_WEB)
                    ? AppInfo.TYPE_WEB : AppInfo.TYPE_INTENT;
            newInfo.keyCode  = entry.getValue().getKeyCode();
            newInfo.name     = entry.getValue().getIconText();
            newInfo.url      = entry.getValue().getUrl();
            newInfo.iconUrl  = entry.getValue().getIcon();
            newInfo.screenOrder = entry.getValue().getScreenOrder();
            newInfo.useKiosk = entry.getValue().isUseKiosk() ? 1 : 0;
            newInfo.intent   = entry.getValue().getIntent();
            appInfos.add(newInfo);
        }

        Collections.sort(appInfos, new AppInfosComparator());

        return appInfos;
    }

    private void getConfiguredApps(Context context, boolean bottom, Map<String, Application> requiredPackages, Map<String, Application> requiredLinks) {
        SettingsHelper config = SettingsHelper.getInstance( context );
        if ( config.getConfig() != null ) {
            List< Application > applications = SettingsHelper.getInstance( context ).getConfig().getApplications();
            for ( Application application : applications ) {
                if (application.isShowIcon() && !application.isRemove() && (bottom == application.isBottom())) {
                    if (application.getType() == null || application.getType().equals(Application.TYPE_APP)) {
                        requiredPackages.put(application.getPkg(), application);
                    } else if (application.getType().equals(Application.TYPE_WEB)) {
                        requiredLinks.put(application.getUrl(), application);
                    } else if (application.getType().equals(Application.TYPE_INTENT)) {
                        requiredLinks.put(application.getIntent(), application);
                    }
                }
            }
        }
    }

    public class AppInfosComparator implements Comparator<AppInfo> {
        @Override
        public int compare(AppInfo o1, AppInfo o2) {
            if (o1.screenOrder == null) {
                if (o2.screenOrder == null) {
                    return 0;
                }
                return 1;
            }
            if (o2.screenOrder == null) {
                return -1;
            }
            return Integer.compare(o1.screenOrder, o2.screenOrder);
        }
    }

}
