package com.hmdm.launcher.dialer;

public class ContactItem {
    public final String name;
    public final String number;
    public final boolean isAllowed;
    public final long contactId;  // Android contacts DB row ID — used to open contact card

    // Full constructor including contactId
    public ContactItem(String name, String number, boolean isAllowed, long contactId) {
        this.name      = name;
        this.number    = number;
        this.isAllowed = isAllowed;
        this.contactId = contactId;
    }

    // Backward-compatible constructor — contactId defaults to 0 (falls back to number lookup)
    public ContactItem(String name, String number, boolean isAllowed) {
        this(name, number, isAllowed, 0L);
    }
}