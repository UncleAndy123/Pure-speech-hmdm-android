/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Pure Speech Fork — IncomingCallActivity
 * Displays the incoming call UI for whitelisted callers.
 * Non-whitelisted callers are rejected before this Activity is ever launched
 * (handled in HmdmInCallService.handleIncomingCall).
 *
 * Dpad-first design for Kyocera E4610 (240x320, physical keypad):
 *   Green call key  → Answer
 *   Red end key     → Reject
 *   Back key        → Reject
 *   Dpad center     → Activates focused button
 *
 * Works fully offline — whitelist read from local MDM config via
 * CallWhitelistManager → SettingsHelper (no server connection needed).
 */

package com.hmdm.launcher.dialer;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Call;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hmdm.launcher.R;
import com.hmdm.launcher.service.HmdmInCallService;
import com.hmdm.launcher.util.CallWhitelistManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
public class IncomingCallActivity extends AppCompatActivity {

    private static final String TAG = "IncomingCallActivity";

    // Intent extra keys — set by HmdmInCallService when launching this Activity
    public static final String EXTRA_CALLER_NAME   = "caller_name";
    public static final String EXTRA_CALLER_NUMBER = "caller_number";

    private String callerNumber;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // MUST be before setContentView() on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        String name   = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        callerNumber  = getIntent().getStringExtra(EXTRA_CALLER_NUMBER);

        // Fallback: use number as display name if no contact was resolved
        if (name == null || name.isEmpty()) name = callerNumber;
        if (callerNumber == null) callerNumber = "";

        Log.d(TAG, "Incoming call UI — name=" + name + " number=" + callerNumber);

        TextView nameView    = findViewById(R.id.incoming_name);
        TextView numberView  = findViewById(R.id.incoming_number);
        Button   answerBtn   = findViewById(R.id.incoming_answer);
        Button   rejectBtn   = findViewById(R.id.incoming_reject);

        nameView.setText(name);
        numberView.setText(callerNumber);

        // -------------------------------------------------------------------------
        // Secondary whitelist check — defense in depth.
        // HmdmInCallService already checked before launching this Activity,
        // but we check again here in case config was updated in the gap,
        // or in case the Activity is somehow started via another path.
        // -------------------------------------------------------------------------
        boolean allowed = CallWhitelistManager.getInstance(this).isAllowed(callerNumber);
        if (!allowed) {
            Log.d(TAG, "Secondary whitelist check — BLOCKING: " + callerNumber);
            rejectCall();
            finish();
            return;
        }

        // Set initial dpad focus on Answer button
        answerBtn.requestFocus();

        // -------------------------------------------------------------------------
        // Click listeners
        // -------------------------------------------------------------------------
        answerBtn.setOnClickListener(v -> answerCall());

        rejectBtn.setOnClickListener(v -> {
            rejectCall();
            finish();
        });

        // -------------------------------------------------------------------------
        // Dpad key listeners — required for Kyocera E4610 hardware keypad
        // -------------------------------------------------------------------------
        answerBtn.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER) {
                    answerCall();
                    return true;
                }
            }
            return false;
        });

        rejectBtn.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER) {
                    rejectCall();
                    finish();
                    return true;
                }
            }
            return false;
        });
    }
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(HmdmInCallService.ACTION_CALL_ENDED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndedReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(callEndedReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(callEndedReceiver); } catch (Exception e) { /* already gone */ }
        // If call is no longer ringing when we lose focus, we're stale — finish.
        // Handles the case where STATE_ACTIVE fired before answerCall() could call finish().
        Call call = HmdmInCallService.getCurrentCall();
        if (call == null || call.getState() != Call.STATE_RINGING) {
            finish();
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }
    private final BroadcastReceiver callEndedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Call ended remotely — closing IncomingCallActivity");
            finish();
        }
    };
    // -------------------------------------------------------------------------
    // Hardware key handling — green/red physical keys on Kyocera E4610
    // These keys fire onKeyDown at the Activity level regardless of focus,
    // so we handle them here rather than on individual views.
    // -------------------------------------------------------------------------
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL:
                // Green call key — answer
                answerCall();
                return true;

            case KeyEvent.KEYCODE_ENDCALL:
                // Red end key — reject
                rejectCall();
                finish();
                return true;

            case KeyEvent.KEYCODE_BACK:
                // Back key — treat as reject to prevent accidental dismissal
                rejectCall();
                finish();
                return true;

            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // -------------------------------------------------------------------------
    // Call control methods
    // -------------------------------------------------------------------------

    /**
     * Answers the current incoming call.
     * Retrieves the Call object from HmdmInCallService static reference.
     * Passing 0 as videoState answers as audio-only call.
     */
    private void answerCall() {
        Log.d(TAG, "answerCall()");
        Call call = HmdmInCallService.getCurrentCall();
        if (call != null) {
            call.answer(0);
        } else {
            Log.w(TAG, "answerCall — no current call found");
        }
        finish();
    }

    /**
     * Rejects the current incoming call.
     * false = do not send SMS rejection message.
     * null  = no rejection message text.
     */
    private void rejectCall() {
        Log.d(TAG, "rejectCall()");
        Call call = HmdmInCallService.getCurrentCall();
        if (call != null) {
            call.reject(false, null);
        } else {
            Log.w(TAG, "rejectCall — no current call found");
        }
    }
}