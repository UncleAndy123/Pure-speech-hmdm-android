package com.hmdm.launcher.dialer;

import android.app.Activity;
import android.content.Intent;
import android.telecom.Call;
import android.view.KeyEvent;

import com.hmdm.launcher.service.HmdmInCallService;

/**
 * Centralizes CALL/ENDCALL hardware key handling so that any screen with
 * an active call in progress routes the user back to InCallActivity,
 * rather than each Activity re-implementing this check.
 *
 * Call handleKeyDown() first from onKeyDown() in any Activity that should
 * respect an in-progress call. Returns true if the key was consumed.
 */
public class CallKeyRouter {

public static boolean handleKeyDown(Activity activity, int keyCode, KeyEvent event) {
    if (keyCode != KeyEvent.KEYCODE_CALL && 
        keyCode != KeyEvent.KEYCODE_ENDCALL && 
        keyCode != KeyEvent.KEYCODE_POWER) {
        return false;
    }

    Call activeCall = HmdmInCallService.getCurrentCall();
    if (activeCall == null) {
        return false;
    }

    // Don't relaunch if we're already showing the in-call screen —
    // avoids stacking duplicate blank instances on repeated key presses.
    if (activity instanceof InCallActivity) {
        return false;
    }

    // If the user pressed ENDCALL or POWER during a call, hang up 
    // immediately rather than just switching to the in-call screen.
    if (keyCode == KeyEvent.KEYCODE_ENDCALL || keyCode == KeyEvent.KEYCODE_POWER) {
        activeCall.disconnect();
        return true;
    }

    String number = (activeCall.getDetails().getHandle() != null)
            ? activeCall.getDetails().getHandle().getSchemeSpecificPart()
            : "";
    boolean isActive = activeCall.getState() == Call.STATE_ACTIVE;

    Intent intent = new Intent(activity, InCallActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    intent.putExtra(InCallActivity.EXTRA_CALLER_NAME, number);   // name resolution not critical here — number is enough
    intent.putExtra(InCallActivity.EXTRA_CALLER_NUMBER, number);
    intent.putExtra(InCallActivity.EXTRA_IS_CONNECTED, isActive);
    activity.startActivity(intent);
    return true;
}
}