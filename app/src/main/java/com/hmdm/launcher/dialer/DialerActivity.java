/*
 * Pure Speech Fork — DialerActivity (updated)
 *
 * Unified dialer + call history in one screen.
 *
 * Tab bar at top:
 *   CONTACTS | ALL | MISSED | IN | OUT
 *
 * CONTACTS tab: existing contact search + dial pad (unchanged behaviour)
 * ALL / MISSED / IN / OUT tabs: call history filtered by type,
 *   reusing CallHistoryAdapter and CallHistoryItem.
 *
 * Tapping a history entry shows a detail dialog (name, number, type,
 * date, duration) with a CALL BACK option.
 *
 * D-pad left/right on the tab row cycles between tabs.
 * D-pad down from any tab moves focus into the list below.
 */

package com.hmdm.launcher.dialer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hmdm.launcher.R;
import com.hmdm.launcher.util.CallWhitelistManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DialerActivity extends AppCompatActivity
        implements ContactsAdapter.OnContactSelectedListener,
        CallHistoryAdapter.OnHistoryItemSelectedListener {

    // -----------------------------------------------------------------------
    // Tab constants
    // -----------------------------------------------------------------------
    private static final int TAB_ALL      = 0;
    private static final int TAB_CONTACTS = 1;
    private static final int TAB_MISSED   = 2;
    private static final int TAB_INCOMING = 3;
    private static final int TAB_OUTGOING = 4;

    private int currentTab = TAB_CONTACTS;

    // -----------------------------------------------------------------------
    // Views — contacts section
    // -----------------------------------------------------------------------
    private EditText      searchField;
    private RecyclerView  contactList;
    private ContactsAdapter contactAdapter;
    private Button        dialButton;
    private List<ContactItem> allContacts = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Views — history section
    // -----------------------------------------------------------------------
    private RecyclerView       historyList;
    private CallHistoryAdapter historyAdapter;
    private TextView           historyEmpty;
    private List<CallHistoryItem> allHistoryItems = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Tab buttons
    // -----------------------------------------------------------------------
    private Button tabAll;
    private Button tabContacts;
    private Button tabMissed;
    private Button tabIncoming;
    private Button tabOutgoing;

    private final Button[] tabButtons = new Button[5];

    // =========================================================================
    // onCreate
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dialer);

        // ---- Tab buttons ----
        tabAll      = findViewById(R.id.tab_all);
        tabContacts = findViewById(R.id.tab_contacts);
        tabMissed   = findViewById(R.id.tab_missed);
        tabIncoming = findViewById(R.id.tab_incoming);
        tabOutgoing = findViewById(R.id.tab_outgoing);
        tabButtons[TAB_ALL]      = tabAll;
        tabButtons[TAB_CONTACTS] = tabContacts;
        tabButtons[TAB_MISSED]   = tabMissed;
        tabButtons[TAB_INCOMING] = tabIncoming;
        tabButtons[TAB_OUTGOING] = tabOutgoing;

        tabAll.setOnClickListener(v      -> switchTab(TAB_ALL));
        tabContacts.setOnClickListener(v -> switchTab(TAB_CONTACTS));
        tabMissed.setOnClickListener(v   -> switchTab(TAB_MISSED));
        tabIncoming.setOnClickListener(v -> switchTab(TAB_INCOMING));
        tabOutgoing.setOnClickListener(v -> switchTab(TAB_OUTGOING));

        // D-pad left/right cycles tabs
        for (int i = 0; i < tabButtons.length; i++) {
            final int idx = i;
            tabButtons[i].setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && idx < tabButtons.length - 1) {
                    switchTab(idx + 1);
                    tabButtons[idx + 1].requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && idx > 0) {
                    switchTab(idx - 1);
                    tabButtons[idx - 1].requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    switchTab(idx);
                    return true;
                }
                return false;
            });
        }

        // ---- Contacts section ----
        searchField   = findViewById(R.id.dialer_search);
        contactList   = findViewById(R.id.dialer_contact_list);
        dialButton    = findViewById(R.id.dialer_dial_button);

        contactAdapter = new ContactsAdapter(this);
        contactList.setLayoutManager(new LinearLayoutManager(this));
        contactList.setAdapter(contactAdapter);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim();
                boolean hasDigits = !query.isEmpty();
                dialButton.setVisibility(hasDigits ? View.VISIBLE : View.GONE);
                filterContacts(query);
            }
        });

        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (event != null &&
                    (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER ||
                            event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                dialCurrentNumber();
                return true;
            }
            return false;
        });

        dialButton.setOnClickListener(v -> dialCurrentNumber());

        // Prefill digit if launched from hardware key
        String prefill = getIntent().getStringExtra("prefill_digit");
        if (prefill != null && !prefill.isEmpty()) {
            searchField.setText(prefill);
            searchField.setSelection(prefill.length());
        }

        // ---- History section ----
        historyList  = findViewById(R.id.dialer_history_list);
        historyEmpty = findViewById(R.id.dialer_history_empty);

        historyAdapter = new CallHistoryAdapter(this);
        historyList.setLayoutManager(new LinearLayoutManager(this));
        historyList.setAdapter(historyAdapter);

        // ---- Initial state ---- This is what the dialer will open up to.
        switchTab(TAB_CONTACTS);
        loadContacts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh history on return from a call
        if (currentTab != TAB_CONTACTS) loadHistory();
    }

    // =========================================================================
    // Tab switching
    // =========================================================================

    private void switchTab(int tab) {
        currentTab = tab;
        updateTabStyles();

        View contactsSection = findViewById(R.id.dialer_contacts_section);
        View historySection  = findViewById(R.id.dialer_history_section);

        if (tab == TAB_CONTACTS) {
            contactsSection.setVisibility(View.VISIBLE);
            historySection.setVisibility(View.GONE);
        } else {
            contactsSection.setVisibility(View.GONE);
            historySection.setVisibility(View.VISIBLE);
            loadHistory();
        }
    }

    private void updateTabStyles() {
        int active   = android.graphics.Color.parseColor("#1565C0");
        int inactive = android.graphics.Color.parseColor("#2C2C2C");
        for (int i = 0; i < tabButtons.length; i++) {
            if (tabButtons[i] != null) {
                tabButtons[i].setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                                i == currentTab ? active : inactive));
            }
        }
    }

    // =========================================================================
    // Contacts
    // =========================================================================

    private void loadContacts() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        AsyncTask.execute(() -> {
            List<ContactItem> loaded = new ArrayList<>();
            CallWhitelistManager wm = CallWhitelistManager.getInstance(this);
            Cursor cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                    },
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name   = cursor.getString(0);
                    String number = cursor.getString(1);
                    long   contactId = cursor.getLong(2);              // ← add this

                    if (name == null) name = number;
                    // Pass isAllowed so ContactsAdapter can show ALLOWED/BLOCKED label
                    loaded.add(new ContactItem(name, number, wm.isAllowed(number)));
                }
                cursor.close();
            }
            allContacts = loaded;
            runOnUiThread(() -> contactAdapter.setContacts(allContacts));
        });
    }

    private void filterContacts(String query) {
        if (query.isEmpty()) {
            contactAdapter.setContacts(allContacts);
            return;
        }
        List<ContactItem> filtered = new ArrayList<>();
        String lower = query.toLowerCase();
        for (ContactItem c : allContacts) {
            if (c.name.toLowerCase().contains(lower) ||
                    c.number.replace(" ", "").contains(query.replace(" ", ""))) {
                filtered.add(c);
            }
        }
        contactAdapter.setContacts(filtered);
    }

    @Override
    public void onContactSelected(ContactItem contact) {
        launchConfirm(contact.name, contact.number);
    }

    private void dialCurrentNumber() {
        String raw = searchField.getText().toString().trim();
        if (!raw.isEmpty()) launchConfirm(raw, raw);
    }

    private void launchConfirm(String name, String number) {
        boolean allowed = CallWhitelistManager.getInstance(this).isAllowed(number);
        Intent intent = new Intent(this, ConfirmCallActivity.class);
        intent.putExtra("name",    name);
        intent.putExtra("number",  number);
        intent.putExtra("allowed", allowed);
        startActivity(intent);
    }

    // =========================================================================
    // Call history
    // =========================================================================

    private void loadHistory() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CALL_LOG}, 2);
            return;
        }
        AsyncTask.execute(() -> {
            List<CallHistoryItem> loaded = new ArrayList<>();
            Cursor cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{
                            CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION
                    },
                    null, null,
                    CallLog.Calls.DATE + " DESC");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name     = cursor.getString(0);
                    String number   = cursor.getString(1);
                    if (name == null || name.isEmpty()) name = lookupContactName(number);
                    int    type     = cursor.getInt(2);
                    long   date     = cursor.getLong(3);
                    long   duration = cursor.getLong(4);

                    if (type == CallLog.Calls.INCOMING_TYPE ||
                            type == CallLog.Calls.OUTGOING_TYPE ||
                            type == CallLog.Calls.MISSED_TYPE) {
                        int mapped;
                        switch (type) {
                            case CallLog.Calls.INCOMING_TYPE: mapped = CallHistoryItem.TYPE_INCOMING; break;
                            case CallLog.Calls.OUTGOING_TYPE: mapped = CallHistoryItem.TYPE_OUTGOING; break;
                            default:                           mapped = CallHistoryItem.TYPE_MISSED;   break;
                        }
                        loaded.add(new CallHistoryItem(name, number, mapped, date, duration));
                    }
                }
                cursor.close();
            }
            allHistoryItems = loaded;
            runOnUiThread(this::applyHistoryFilter);
        });
    }

    private void applyHistoryFilter() {
        int filter;
        switch (currentTab) {
            case TAB_MISSED:   filter = CallHistoryItem.TYPE_MISSED;   break;
            case TAB_INCOMING: filter = CallHistoryItem.TYPE_INCOMING; break;
            case TAB_OUTGOING: filter = CallHistoryItem.TYPE_OUTGOING; break;
            default:           filter = -1; break; // ALL
        }

        List<CallHistoryItem> filtered = new ArrayList<>();
        for (CallHistoryItem item : allHistoryItems) {
            if (filter == -1 || item.type == filter) filtered.add(item);
        }

        historyAdapter.setItems(filtered);

        if (filtered.isEmpty()) {
            historyEmpty.setVisibility(View.VISIBLE);
            historyEmpty.setText(filter == -1 ? "No call history" :
                    filter == CallHistoryItem.TYPE_MISSED   ? "No missed calls" :
                            filter == CallHistoryItem.TYPE_INCOMING ? "No incoming calls" :
                                    "No outgoing calls");
        } else {
            historyEmpty.setVisibility(View.GONE);
        }
    }

    /**
     * Called when user selects a call history entry.
     * Shows a detail dialog with full call info and a Call Back option.
     */
    @Override
    public void onHistoryItemSelected(CallHistoryItem item) {
        showCallDetailDialog(item);
    }

    private void showCallDetailDialog(CallHistoryItem item) {
        // Format date/time
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault());
        String dateStr = sdf.format(new Date(item.date));

        // Build duration string with hours if needed
        String durationStr;
        if (item.type == CallHistoryItem.TYPE_MISSED) {
            durationStr = "Missed";
        } else if (item.duration <= 0) {
            durationStr = "0 seconds";
        } else {
            long h = item.duration / 3600;
            long m = (item.duration % 3600) / 60;
            long s = item.duration % 60;
            if (h > 0) durationStr = h + "h " + m + "m " + s + "s";
            else if (m > 0) durationStr = m + "m " + s + "s";
            else durationStr = s + "s";
        }

        String typeLabel = item.getTypeLabel();
        String message =
                "Name:     " + item.name + "\n" +
                        "Number:   " + item.number + "\n" +
                        "Type:     " + typeLabel + "\n" +
                        "Date:     " + dateStr + "\n" +
                        "Duration: " + durationStr;

        boolean allowed = CallWhitelistManager.getInstance(this).isAllowed(item.number);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Call Details")
                .setMessage(message)
                .setNegativeButton("Close", (d, w) -> d.dismiss());

        if (allowed) {
            builder.setPositiveButton("Call Back", (d, w) -> {
                d.dismiss();
                launchConfirm(item.name, item.number);
            });
        }

        builder.create().show();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String lookupContactName(String number) {
        if (number == null || number.isEmpty()) return number;
        Uri lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));
        Cursor c = getContentResolver().query(lookupUri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                null, null, null);
        try {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } finally {
            if (c != null) c.close();
        }
        return number;
    }

    // =========================================================================
    // Hardware keys
    // =========================================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_CALL:
                dialCurrentNumber();
                return true;
            case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3: case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6: case KeyEvent.KEYCODE_7: case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                if (currentTab == TAB_CONTACTS) {
                    String digit = String.valueOf(keyCode - KeyEvent.KEYCODE_0);
                    searchField.append(digit);
                    searchField.requestFocus();
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }
}