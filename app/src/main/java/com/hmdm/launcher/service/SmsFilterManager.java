/*
 * Pure Speech Fork — SmsFilterManager
 *
 * Responsible for reading SMS/MMS filter settings from the locally stored
 * MDM config and pushing them to the DPAD-Messaging app via
 * DevicePolicyManager.setApplicationRestrictions().
 *
 * This is intentionally a separate class from CallWhitelistManager.
 * CallWhitelistManager reads config and checks numbers — it owns the
 * call enforcement logic. SmsFilterManager owns the SMS enforcement
 * config delivery pipeline: read MDM settings → build Bundle → push to
 * the messaging app via DPM.
 *
 * Config keys (set in Headwind MDM admin > Configurations > Application Settings,
 * package = com.hmdm.launcher):
 *
 *   sms_filter_mode      String   "off" | "whitelist" | "blocklist"
 *   sms_allowed_numbers  String   CSV of E.164 numbers, or "*" for all
 *   sms_blocked_numbers  String   CSV of E.164 numbers to block
 *
 * The messaging app (DPAD-Messaging) reads these via RestrictionsManager on
 * every incoming SMS/MMS in SmsWhitelistManager.kt.
 *
 * Call sites:
 *   - Wherever config is applied after a server sync (e.g. ProxyActivity,
 *     or wherever SettingsHelper.getInstance().applyConfig() is called).
 *   - pushIfNeeded() is idempotent — safe to call on every config refresh.
 */

package com.hmdm.launcher.service;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ApplicationSetting;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;

import java.util.List;

public class SmsFilterManager {

    private static final String TAG = "SmsFilterManager";

    // ── MDM application setting keys (set per-package in Headwind admin) ────
    public static final String KEY_MODE    = "sms_filter_mode";
    public static final String KEY_ALLOWED = "sms_allowed_numbers";
    public static final String KEY_BLOCKED = "sms_blocked_numbers";

    // ── Target messaging app ─────────────────────────────────────────────────
    // Change this if you fork DPAD-Messaging under a different package name.
    private static final String MESSAGING_PACKAGE = "com.dpadsms";

    // ── Valid mode values ────────────────────────────────────────────────────
    public static final String MODE_OFF        = "off";
    public static final String MODE_WHITELIST  = "whitelist";
    public static final String MODE_BLOCKLIST  = "blocklist";

    private static SmsFilterManager instance;
    private final Context context;

    private SmsFilterManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized SmsFilterManager getInstance(Context context) {
        if (instance == null) {
            instance = new SmsFilterManager(context);
        }
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads SMS filter settings from the locally stored MDM config and pushes
     * them to DPAD-Messaging via setApplicationRestrictions().
     *
     * Safe to call on every config refresh — if the Device Owner check fails
     * (e.g. during early init before DPM is ready) it logs a warning and
     * returns false without throwing.
     *
     * @return true if the push succeeded, false otherwise.
     */
    public boolean pushIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "setApplicationRestrictions requires API 21+; skipping");
            return false;
        }

        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            Log.w(TAG, "DevicePolicyManager unavailable");
            return false;
        }
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.w(TAG, "Not Device Owner — cannot push restrictions to " + MESSAGING_PACKAGE);
            return false;
        }

        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        Bundle restrictions = buildRestrictionsBundle();

        try {
            dpm.setApplicationRestrictions(admin, MESSAGING_PACKAGE, restrictions);
            Log.i(TAG, "SMS filter pushed to " + MESSAGING_PACKAGE
                    + " [mode=" + restrictions.getString(KEY_MODE)
                    + " allowed=" + restrictions.getString(KEY_ALLOWED)
                    + " blocked=" + restrictions.getString(KEY_BLOCKED) + "]");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException pushing SMS restrictions — not Device Owner?", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error pushing SMS restrictions", e);
            return false;
        }
    }

    /**
     * Clears all SMS filter restrictions from the messaging app (sets mode to "off").
     * Useful if the feature is disabled server-side or for a device being unenrolled.
     */
    public void clearRestrictions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) return;

        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        Bundle cleared = new Bundle();
        cleared.putString(KEY_MODE,    MODE_OFF);
        cleared.putString(KEY_ALLOWED, "");
        cleared.putString(KEY_BLOCKED, "");
        try {
            dpm.setApplicationRestrictions(admin, MESSAGING_PACKAGE, cleared);
            Log.i(TAG, "SMS filter restrictions cleared for " + MESSAGING_PACKAGE);
        } catch (Exception e) {
            Log.w(TAG, "Failed to clear SMS restrictions", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reads the three SMS filter keys from the locally stored MDM config
     * and builds the Bundle that will be pushed to the messaging app.
     *
     * Falls back to MODE_OFF / empty strings if the keys are absent, so the
     * messaging app behaves safely even if the server hasn't set them yet.
     */
    private Bundle buildRestrictionsBundle() {
        String mode    = getSetting(KEY_MODE,    MODE_OFF);
        String allowed = getSetting(KEY_ALLOWED, "");
        String blocked = getSetting(KEY_BLOCKED, "");

        // Normalise mode — reject unknown values to avoid silent misbehaviour.
        if (!MODE_WHITELIST.equals(mode) && !MODE_BLOCKLIST.equals(mode)) {
            mode = MODE_OFF;
        }

        Bundle b = new Bundle();
        b.putString(KEY_MODE,    mode);
        b.putString(KEY_ALLOWED, allowed);
        b.putString(KEY_BLOCKED, blocked);
        return b;
    }

    /**
     * Looks up a named application setting for this launcher package
     * from the locally cached MDM config. Returns {@code defaultValue}
     * if the config is unavailable or the key is absent.
     */
    private String getSetting(String key, String defaultValue) {
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (config == null) return defaultValue;
        List<ApplicationSetting> settings = config.getApplicationSettings();
        if (settings == null) return defaultValue;

        // Temporary diagnostic — remove once confirmed
        Log.d(TAG, "getApplicationSettings() returned " + settings.size() + " entries:");
        for (ApplicationSetting s : settings) {
            Log.d(TAG, "  pkg=" + s.getPackageId() + " name=" + s.getName() + " value=" + s.getValue());
        }

        for (ApplicationSetting s : settings) {
            if (key.equals(s.getName())) {
                String v = s.getValue();
                return (v != null) ? v.trim() : defaultValue;
            }
        }
        return defaultValue;
    }
}
