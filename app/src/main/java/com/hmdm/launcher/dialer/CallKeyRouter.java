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
        if (keyCode != KeyEvent.KEYCODE_CALL && keyCode != KeyEvent.KEYCODE_ENDCALL) {
            return false;
        }

        Call activeCall = HmdmInCallService.getCurrentCall();
        if (activeCall == null) {
            return false; // No active call — let the caller's normal key handling proceed.
        }

        // A call is in progress: always surface it instead of taking any
        // other action (dial, lock, hangup) directly from this screen.
        Intent intent = new Intent(activity, InCallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(intent);
        return true;
    }
}