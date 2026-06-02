package com.hmdm.launcher.dialer;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ContentUris;

import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.ContactsContract;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hmdm.launcher.R;

import java.util.ArrayList;
import java.util.List;

public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.ViewHolder> {

    public interface OnContactSelectedListener {
        void onContactSelected(ContactItem contact);
    }

    private List<ContactItem> contacts = new ArrayList<>();
    private final OnContactSelectedListener listener;

    public ContactsAdapter(OnContactSelectedListener listener) {
        this.listener = listener;
    }

    public void setContacts(List<ContactItem> contacts) {
        this.contacts = contacts;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(contacts.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    // =========================================================================
    // ViewHolder
    // =========================================================================

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView nameView;
        private final TextView numberView;
        private final View     statusDot;
        private final TextView statusLabel;
        private final Button   callButton;

        ViewHolder(View itemView) {
            super(itemView);
            nameView    = itemView.findViewById(R.id.contact_name);
            numberView  = itemView.findViewById(R.id.contact_number);
            statusDot   = itemView.findViewById(R.id.contact_status_dot);
            statusLabel = itemView.findViewById(R.id.contact_status_label);
            callButton  = itemView.findViewById(R.id.contact_call_button);
        }

        void bind(ContactItem contact, OnContactSelectedListener listener) {
            if (nameView == null) return; // layout inflate failed — skip

            nameView.setText(contact.name);
            numberView.setText(contact.number);


            // Whitelist check — uses pre-computed value from ContactItem
            // (computed at load time in DialerActivity.loadContacts())
            final boolean isAllowed = contact.isAllowed;

            // ---- Appearance ----
            if (isAllowed) {
                statusDot.setBackgroundResource(R.drawable.status_dot);
                nameView.setTextColor(Color.parseColor("#1C1C1E"));
                numberView.setTextColor(Color.parseColor("#8E8E93"));
                statusLabel.setVisibility(View.GONE);
                callButton.setVisibility(View.VISIBLE);
            } else {
                statusDot.setBackgroundColor(Color.parseColor("#C7C7CC"));
                nameView.setTextColor(Color.parseColor("#8E8E93"));
                numberView.setTextColor(Color.parseColor("#C7C7CC"));
                statusLabel.setVisibility(View.VISIBLE);
                callButton.setVisibility(View.GONE);
            }
            // Add this near the top of bind(), after the isAllowed block
            callButton.setFocusable(false);

            // ---- Focus color flip for d-pad readability ----
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    nameView.setTextColor(Color.WHITE);
                    numberView.setTextColor(Color.parseColor("#E5E5EA"));
                    if (!isAllowed) statusLabel.setTextColor(Color.WHITE);
                } else {
                    nameView.setTextColor(isAllowed
                            ? Color.parseColor("#1C1C1E")
                            : Color.parseColor("#8E8E93"));
                    numberView.setTextColor(isAllowed
                            ? Color.parseColor("#8E8E93")
                            : Color.parseColor("#C7C7CC"));
                    if (!isAllowed) statusLabel.setTextColor(Color.parseColor("#FF3B30"));
                }
            });

            // ---- Row click → open contact in system contacts app ----
            itemView.setOnClickListener(v -> openContact(v.getContext(), contact));

            // ---- Row d-pad center → open contact ----
            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        openContact(v.getContext(), contact);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        // Move focus to CALL button if visible
                        if (isAllowed && callButton.getVisibility() == View.VISIBLE) {
                            callButton.setFocusable(true);
                            callButton.requestFocus();
                            return true;
                        }
                        return false;
                    case KeyEvent.KEYCODE_CALL:
                        // Physical green call key on row — call if allowed
                        if (isAllowed) {
                            listener.onContactSelected(contact);
                            return true;
                        }
                        return false;
                }
                return false;
            });

            // ---- CALL button click → place call ----
            if (isAllowed) {
                callButton.setOnClickListener(v -> listener.onContactSelected(contact));
                callButton.setOnKeyListener((v, keyCode, event) -> {

                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                        case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_CALL:
                            listener.onContactSelected(contact);
                            return true;

                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            // Move focus back to row
                            itemView.requestFocus();
                            return true;
                    }
                    return false;
                });
            }// In the callButton.setOnKeyListener block, add a focus listener
            callButton.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    callButton.setFocusable(false);
                }
            });
        }

        // ---------------------------------------------------------------------
        // Open contact in the system contacts app
        // Uses contactId if available; falls back to phone number lookup URI
        // ---------------------------------------------------------------------
        private void openContact(Context context, ContactItem contact) {
            try {
                long id = contact.contactId;

                // If contactId wasn't populated at load time, look it up now
                if (id <= 0) {
                    Uri lookupUri = Uri.withAppendedPath(
                            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                            Uri.encode(contact.number));
                    Cursor c = context.getContentResolver().query(
                            lookupUri,
                            new String[]{ContactsContract.PhoneLookup._ID},
                            null, null, null);
                    if (c != null) {
                        if (c.moveToFirst()) id = c.getLong(0);
                        c.close();
                    }
                }

                if (id > 0) {
                    Uri contactUri = ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI, id);
                    context.startActivity(new Intent(Intent.ACTION_VIEW, contactUri));
                } else {
                    // Contact genuinely not found — open contacts list
                    context.startActivity(new Intent(Intent.ACTION_VIEW,
                            ContactsContract.Contacts.CONTENT_URI));
                }
            } catch (Exception e) {
                android.util.Log.w("ContactsAdapter",
                        "Could not open contact: " + e.getMessage());
            }
        }
    }
}