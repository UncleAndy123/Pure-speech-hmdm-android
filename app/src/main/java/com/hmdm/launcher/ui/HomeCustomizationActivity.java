/*
 * Pure Speech Fork — HomeCustomizationActivity
 *
 * Allows users to personalize the launcher home screen:
 *   - Background: pick from gallery, choose a solid color, or reset to MDM default
 *   - App visibility: hide/show any app the SERVER has allowed — cannot add
 *     apps that are not in the MDM config (security enforced by reading only
 *     from SettingsHelper.getConfig().getApplications())
 *
 * Access: long press on launcher home screen background → MainActivity fires
 *         startActivityForResult(HomeCustomizationActivity, 3001)
 *         On RESULT_OK, MainActivity re-applies background prefs.
 *
 * Preferences stored under key "home_prefs":
 *   wallpaper_type    = "gallery" | "color" | "default"
 *   wallpaper_color   = #RRGGBB string
 *   wallpaper_uri     = content URI string (gallery pick)
 *   hidden_packages   = JSON array of package name strings
 *
 * NOTE: The MDM-pushed backgroundColor / backgroundImageUrl always takes
 * priority over user prefs. User wallpaper is only applied when the MDM
 * config has no background set.
 */

package com.hmdm.launcher.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeCustomizationActivity extends AppCompatActivity {

    public static final String PREFS_NAME          = "home_prefs";
    public static final String KEY_WALLPAPER_TYPE  = "wallpaper_type";
    public static final String KEY_WALLPAPER_COLOR = "wallpaper_color";
    public static final String KEY_WALLPAPER_URI   = "wallpaper_uri";
    public static final String KEY_HIDDEN_PACKAGES = "hidden_packages";

    public static final String WALLPAPER_DEFAULT = "default";
    public static final String WALLPAPER_GALLERY = "gallery";
    public static final String WALLPAPER_COLOR   = "color";

    private static final int REQUEST_PICK_IMAGE = 2001;

    // Preset solid colours
    private static final int[] PRESET_COLORS = {
            0xFF000000, // Black
            0xFF121212, // Near black
            0xFF1A237E, // Deep blue
            0xFF1B5E20, // Deep green
            0xFF4A148C, // Deep purple
            0xFF880E4F, // Deep pink
            0xFF263238, // Dark slate
            0xFF37474F, // Blue grey
    };
    private static final String[] PRESET_LABELS = {
            "Black", "Dark", "Navy", "Forest",
            "Purple", "Burgundy", "Slate", "Dusk"
    };

    private SharedPreferences prefs;
    private TextView currentWallpaperView;
    private AppVisibilityAdapter appAdapter;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_customization);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        setupWallpaperSection();
        setupAppVisibilitySection();
        setupSaveButton();
    }

    // =========================================================================
    // Wallpaper section
    // =========================================================================

    private void setupWallpaperSection() {
        currentWallpaperView = findViewById(R.id.custom_current_wallpaper);
        updateWallpaperLabel();

        // Gallery button
        Button galleryBtn = findViewById(R.id.custom_gallery_btn);
        galleryBtn.setOnClickListener(v -> pickFromGallery());
        galleryBtn.setOnKeyListener(dpadOk(v -> pickFromGallery()));

        // Preset colour buttons
        int[] btnIds = {
                R.id.color_btn_0, R.id.color_btn_1, R.id.color_btn_2, R.id.color_btn_3,
                R.id.color_btn_4, R.id.color_btn_5, R.id.color_btn_6, R.id.color_btn_7
        };
        for (int i = 0; i < btnIds.length; i++) {
            Button btn = findViewById(btnIds[i]);
            if (btn == null) continue;
            final int color = PRESET_COLORS[i];
            final String label = PRESET_LABELS[i];
            btn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(color));
            btn.setText(label);
            btn.setTextColor(Color.WHITE);
            btn.setOnClickListener(v -> applyColorBackground(color, label));
            btn.setOnKeyListener(dpadOk(v -> applyColorBackground(color, label)));
        }

        // Reset button
        Button resetBtn = findViewById(R.id.custom_reset_btn);
        if (resetBtn != null) {
            resetBtn.setOnClickListener(v -> resetBackground());
            resetBtn.setOnKeyListener(dpadOk(v -> resetBackground()));
        }
    }

    private void pickFromGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void applyColorBackground(int color, String label) {
        String hex = String.format("#%06X", (0xFFFFFF & color));
        prefs.edit()
                .putString(KEY_WALLPAPER_TYPE, WALLPAPER_COLOR)
                .putString(KEY_WALLPAPER_COLOR, hex)
                .remove(KEY_WALLPAPER_URI)
                .apply();
        updateWallpaperLabel();
        Toast.makeText(this, label + " background set", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
    }

    private void resetBackground() {
        prefs.edit()
                .putString(KEY_WALLPAPER_TYPE, WALLPAPER_DEFAULT)
                .remove(KEY_WALLPAPER_COLOR)
                .remove(KEY_WALLPAPER_URI)
                .apply();
        updateWallpaperLabel();
        Toast.makeText(this, "Background reset to MDM default", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
    }

    private void updateWallpaperLabel() {
        String type = prefs.getString(KEY_WALLPAPER_TYPE, WALLPAPER_DEFAULT);
        switch (type) {
            case WALLPAPER_GALLERY:
                currentWallpaperView.setText("Background: custom image");
                break;
            case WALLPAPER_COLOR:
                String color = prefs.getString(KEY_WALLPAPER_COLOR, "");
                currentWallpaperView.setText("Background: solid color " + color);
                break;
            default:
                currentWallpaperView.setText("Background: MDM default");
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // Persist read permission across reboots
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    // Some providers don't support persistable permissions — continue anyway
                }
                prefs.edit()
                        .putString(KEY_WALLPAPER_TYPE, WALLPAPER_GALLERY)
                        .putString(KEY_WALLPAPER_URI, uri.toString())
                        .remove(KEY_WALLPAPER_COLOR)
                        .apply();
                updateWallpaperLabel();
                Toast.makeText(this, "Background image set", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
            }
        }
    }

    // =========================================================================
    // App visibility section
    //
    // SECURITY: We only list apps that are in the MDM server config AND have
    // showIcon=true and remove=false. The user can hide (suppress from grid)
    // but cannot surface any app the server did not allow.
    // =========================================================================

    private void setupAppVisibilitySection() {
        RecyclerView recycler = findViewById(R.id.custom_app_list);
        if (recycler == null) return;

        Set<String> hidden = loadHiddenPackages();
        List<AppVisibilityItem> items = buildAppList(hidden);

        appAdapter = new AppVisibilityAdapter(items);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(appAdapter);
    }

    /**
     * Builds the list from the MDM config only — never from installed packages.
     * Apps not in the server config cannot appear here.
     */
    private List<AppVisibilityItem> buildAppList(Set<String> hidden) {
        List<AppVisibilityItem> items = new ArrayList<>();
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (config == null || config.getApplications() == null) return items;

        for (Application app : config.getApplications()) {
            // Mirror exactly the same filter AppShortcutManager uses
            if (!app.isShowIcon() || app.isRemove()) continue;
            if (app.getType() != null && !app.getType().equals(Application.TYPE_APP)) continue;
            if (app.getPkg() == null || app.getPkg().isEmpty()) continue;

            AppVisibilityItem item = new AppVisibilityItem();
            item.packageName = app.getPkg();
            item.label = app.getName() != null ? app.getName() : app.getPkg();
            item.visible = !hidden.contains(app.getPkg());
            items.add(item);
        }
        return items;
    }

    // =========================================================================
    // Save button — persists hidden packages list
    // =========================================================================

    private void setupSaveButton() {
        Button saveBtn = findViewById(R.id.custom_save_btn);
        if (saveBtn == null) return;
        saveBtn.setOnClickListener(v -> saveAndFinish());
        saveBtn.setOnKeyListener(dpadOk(v -> saveAndFinish()));
    }

    private void saveAndFinish() {
        if (appAdapter != null) {
            Set<String> nowHidden = new HashSet<>();
            for (AppVisibilityItem item : appAdapter.getItems()) {
                if (!item.visible) nowHidden.add(item.packageName);
            }
            saveHiddenPackages(nowHidden);
        }
        setResult(RESULT_OK);
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    // =========================================================================
    // SharedPreferences helpers
    // =========================================================================

    private Set<String> loadHiddenPackages() {
        Set<String> result = new HashSet<>();
        String json = prefs.getString(KEY_HIDDEN_PACKAGES, null);
        if (json == null) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
        } catch (JSONException e) {
            // Corrupted prefs — start fresh
        }
        return result;
    }

    /**
     * Static helper so AppShortcutManager can read the hidden set without
     * instantiating this activity.
     */
    public static Set<String> loadHiddenPackages(android.content.Context context) {
        Set<String> result = new HashSet<>();
        SharedPreferences p = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = p.getString(KEY_HIDDEN_PACKAGES, null);
        if (json == null) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
        } catch (JSONException e) { /* ignore */ }
        return result;
    }

    private void saveHiddenPackages(Set<String> hidden) {
        JSONArray arr = new JSONArray();
        for (String pkg : hidden) arr.put(pkg);
        prefs.edit().putString(KEY_HIDDEN_PACKAGES, arr.toString()).apply();
    }

    // =========================================================================
    // Key handling
    // =========================================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private View.OnKeyListener dpadOk(View.OnClickListener action) {
        return (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == KeyEvent.KEYCODE_ENTER)) {
                action.onClick(v);
                return true;
            }
            return false;
        };
    }

    // =========================================================================
    // App visibility data model
    // =========================================================================

    static class AppVisibilityItem {
        String packageName;
        String label;
        boolean visible;
    }

    // =========================================================================
    // App visibility RecyclerView adapter
    // =========================================================================

    static class AppVisibilityAdapter
            extends RecyclerView.Adapter<AppVisibilityAdapter.VH> {

        private final List<AppVisibilityItem> items;

        AppVisibilityAdapter(List<AppVisibilityItem> items) {
            this.items = items;
        }

        List<AppVisibilityItem> getItems() { return items; }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app_customize, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            AppVisibilityItem item = items.get(position);
            holder.label.setText(item.label);
            holder.pkg.setText(item.packageName);
            holder.toggle.setChecked(item.visible);
            holder.toggle.setOnCheckedChangeListener((btn, checked) -> item.visible = checked);

            // D-pad toggle support
            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == KeyEvent.KEYCODE_ENTER)) {
                    holder.toggle.setChecked(!holder.toggle.isChecked());
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView label, pkg;
            Switch toggle;
            VH(View v) {
                super(v);
                label  = v.findViewById(R.id.customize_app_label);
                pkg    = v.findViewById(R.id.customize_app_pkg);
                toggle = v.findViewById(R.id.customize_app_toggle);
            }
        }
    }
}