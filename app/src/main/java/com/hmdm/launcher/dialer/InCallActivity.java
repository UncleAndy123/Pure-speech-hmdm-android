/*
 * Pure Speech Fork — InCallActivity (updated)
 *
 * Changes from previous version:
 *   - KEYPAD toggle button added to controls row
 *   - DTMF dialpad section (3x4 grid + digit display) shown/hidden on toggle
 *   - Each dialpad button plays tone on press and stops on release
 *   - Typed digits accumulate in incall_dialed_digits TextView
 *   - Hardware number keys also send DTMF when dialpad is open
 */

package com.hmdm.launcher.dialer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hmdm.launcher.R;
import com.hmdm.launcher.service.HmdmInCallService;

import java.util.Locale;

public class InCallActivity extends AppCompatActivity {

    private static final String TAG = "InCallActivity";

    public static final String EXTRA_CALLER_NAME   = "caller_name";
    public static final String EXTRA_CALLER_NUMBER = "caller_number";
    public static final String EXTRA_IS_CONNECTED  = "is_connected";

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------
    private TextView     statusView;
    private TextView     timerView;
    private TextView     nameView;
    private TextView     numberView;
    private Button       muteBtn;
    private Button       keypadBtn;
    private Button       speakerBtn;
    private Button       hangupBtn;
    private LinearLayout dialpadSection;
    private TextView     dialedDigitsView;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private boolean isMuted       = false;
    private boolean isSpeaker     = false;
    private boolean isConnected   = false;
    private boolean dialpadVisible = false;
    private final StringBuilder dialedDigits = new StringBuilder();

    // -------------------------------------------------------------------------
    // Timer
    // -------------------------------------------------------------------------
    private final Handler  timerHandler  = new Handler(Looper.getMainLooper());
    private long           callStartTime = 0;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected) return;
            long elapsed = System.currentTimeMillis() - callStartTime;
            long seconds = (elapsed / 1000) % 60;
            long minutes = (elapsed / 1000) / 60;
            long hours   = minutes / 60;
            minutes      = minutes % 60;
            String formatted = (hours > 0)
                    ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                    : String.format(Locale.US, "%d:%02d", minutes, seconds);
            timerView.setText(formatted);
            timerHandler.postDelayed(this, 1000);
        }
    };

    // -------------------------------------------------------------------------
    // Broadcast receiver — HmdmInCallService signals STATE_ACTIVE
    // -------------------------------------------------------------------------
    private final BroadcastReceiver connectedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Received ACTION_CALL_CONNECTED broadcast");
            transitionToConnected();
        }
    };

    // -------------------------------------------------------------------------
    // Call state callback — handles remote hang-up etc.
    // -------------------------------------------------------------------------
    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            Log.d(TAG, "callCallback: state=" + state);
            if (state == Call.STATE_ACTIVE) {
                runOnUiThread(() -> transitionToConnected());
            } else if (state == Call.STATE_DISCONNECTED ||
                    state == Call.STATE_DISCONNECTING) {
                runOnUiThread(() -> finish());
            }
        }
    };

    // =========================================================================
    // onCreate
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Show over lock screen on API 27+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_in_call);

        String name   = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        String number = getIntent().getStringExtra(EXTRA_CALLER_NUMBER);
        isConnected   = getIntent().getBooleanExtra(EXTRA_IS_CONNECTED, false);

        if (name == null || name.isEmpty()) name = number;
        if (number == null) number = "";

        Log.d(TAG, "InCallActivity — " + name + " / " + number + " connected=" + isConnected);

        // Bind views
        statusView       = findViewById(R.id.incall_status);
        timerView        = findViewById(R.id.incall_timer);
        nameView         = findViewById(R.id.incall_name);
        numberView       = findViewById(R.id.incall_number);
        muteBtn          = findViewById(R.id.incall_mute);
        keypadBtn        = findViewById(R.id.incall_keypad);
        speakerBtn       = findViewById(R.id.incall_speaker);
        hangupBtn        = findViewById(R.id.incall_hangup);
        dialpadSection   = findViewById(R.id.incall_dialpad_section);
        dialedDigitsView = findViewById(R.id.incall_dialed_digits);

        nameView.setText(name);
        numberView.setText(number);

        if (isConnected) transitionToConnected();
        else showCallingState();

        // Register call callback
        Call call = HmdmInCallService.getCurrentCall();
        if (call != null) {
            call.registerCallback(callCallback);
        } else {
            Log.w(TAG, "No current call on create — finishing");
            finish();
            return;
        }

        hangupBtn.requestFocus();

        // ---- Mute ----
        muteBtn.setOnClickListener(v -> toggleMute());
        muteBtn.setOnKeyListener(dpadOk(v -> toggleMute()));

        // ---- Keypad toggle ----
        keypadBtn.setOnClickListener(v -> toggleDialpad());
        keypadBtn.setOnKeyListener(dpadOk(v -> toggleDialpad()));

        // ---- Speaker ----
        speakerBtn.setOnClickListener(v -> toggleSpeaker());
        speakerBtn.setOnKeyListener(dpadOk(v -> toggleSpeaker()));

        // ---- Hang up ----
        hangupBtn.setOnClickListener(v -> hangUp());
        hangupBtn.setOnKeyListener(dpadOk(v -> hangUp()));

        // ---- Wire up DTMF buttons ----
        wireDtmfButton(R.id.dtmf_0, '0');
        wireDtmfButton(R.id.dtmf_1, '1');
        wireDtmfButton(R.id.dtmf_2, '2');
        wireDtmfButton(R.id.dtmf_3, '3');
        wireDtmfButton(R.id.dtmf_4, '4');
        wireDtmfButton(R.id.dtmf_5, '5');
        wireDtmfButton(R.id.dtmf_6, '6');
        wireDtmfButton(R.id.dtmf_7, '7');
        wireDtmfButton(R.id.dtmf_8, '8');
        wireDtmfButton(R.id.dtmf_9, '9');
        wireDtmfButton(R.id.dtmf_star, '*');
        wireDtmfButton(R.id.dtmf_hash, '#');
    }

    // =========================================================================
    // DTMF
    // =========================================================================

    /**
     * Wires touch + dpad for a single DTMF button.
     * Tone plays while finger/key is held, stops on release.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void wireDtmfButton(int viewId, char tone) {
        Button btn = findViewById(viewId);
        if (btn == null) return;

        // Touch: play on ACTION_DOWN, stop on ACTION_UP / CANCEL
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sendDtmf(tone);
                    return false; // let click through too
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopDtmf();
                    appendDialedDigit(tone);
                    return false;
            }
            return false;
        });

        // D-pad center: play on DOWN, stop on UP
        btn.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    sendDtmf(tone);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    stopDtmf();
                    appendDialedDigit(tone);
                }
                return true;
            }
            return false;
        });
    }

    private void sendDtmf(char tone) {
        Call call = HmdmInCallService.getCurrentCall();
        if (call != null) {
            try { call.playDtmfTone(tone); }
            catch (Exception e) { Log.w(TAG, "playDtmfTone failed: " + e.getMessage()); }
        }
    }

    private void stopDtmf() {
        Call call = HmdmInCallService.getCurrentCall();
        if (call != null) {
            try { call.stopDtmfTone(); }
            catch (Exception e) { Log.w(TAG, "stopDtmfTone failed: " + e.getMessage()); }
        }
    }

    private void appendDialedDigit(char digit) {
        dialedDigits.append(digit);
        dialedDigitsView.setText(dialedDigits.toString());
    }

    // =========================================================================
    // Dialpad toggle
    // =========================================================================

    private void toggleDialpad() {
        dialpadVisible = !dialpadVisible;
        dialpadSection.setVisibility(dialpadVisible ? View.VISIBLE : View.GONE);
        keypadBtn.setText(dialpadVisible ? "HIDE PAD" : "KEYPAD");
        keypadBtn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(
                                dialpadVisible ? "#4A148C" : "#1565C0")));
        if (dialpadVisible) {
            // Focus first dialpad button
            Button first = findViewById(R.id.dtmf_1);
            if (first != null) first.requestFocus();
        } else {
            hangupBtn.requestFocus();
        }
    }

    // =========================================================================
    // Hardware keys — send DTMF for number keys when dialpad is open
    // =========================================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // When dialpad is visible, number/star/pound keys send DTMF
        if (dialpadVisible) {
            char tone = keyCodeToTone(keyCode);
            if (tone != 0) {
                sendDtmf(tone);
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENDCALL:
                hangUp();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (dialpadVisible) { toggleDialpad(); return true; }
                return true; // back does nothing else during a call
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (dialpadVisible) {
            char tone = keyCodeToTone(keyCode);
            if (tone != 0) {
                stopDtmf();
                appendDialedDigit(tone);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private char keyCodeToTone(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_0: return '0';
            case KeyEvent.KEYCODE_1: return '1';
            case KeyEvent.KEYCODE_2: return '2';
            case KeyEvent.KEYCODE_3: return '3';
            case KeyEvent.KEYCODE_4: return '4';
            case KeyEvent.KEYCODE_5: return '5';
            case KeyEvent.KEYCODE_6: return '6';
            case KeyEvent.KEYCODE_7: return '7';
            case KeyEvent.KEYCODE_8: return '8';
            case KeyEvent.KEYCODE_9: return '9';
            case KeyEvent.KEYCODE_STAR:  return '*';
            case KeyEvent.KEYCODE_POUND: return '#';
            default: return 0;
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(HmdmInCallService.ACTION_CALL_CONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectedReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectedReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(connectedReceiver); } catch (Exception e) { /* already unregistered */ }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);
        Call call = HmdmInCallService.getCurrentCall();
        if (call != null) {
            try { call.unregisterCallback(callCallback); } catch (Exception e) { /* ignore */ }
        }
        stopDtmf(); // safety — stop any held tone
        Log.d(TAG, "onDestroy");
    }

    // =========================================================================
    // UI state transitions
    // =========================================================================

    private void showCallingState() {
        statusView.setText("Calling...");
        statusView.setTextColor(android.graphics.Color.parseColor("#FFA000"));
        timerView.setText("");
        timerView.setVisibility(View.INVISIBLE);
    }

    private void transitionToConnected() {
        if (isConnected) return;
        isConnected   = true;
        callStartTime = System.currentTimeMillis();
        statusView.setText("Connected");
        statusView.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
        timerView.setVisibility(View.VISIBLE);
        timerHandler.post(timerRunnable);
        Log.d(TAG, "Transitioned to CONNECTED");
    }

    // =========================================================================
    // Call control
    // =========================================================================

    private void hangUp() {
        Log.d(TAG, "hangUp()");
        stopDtmf();
        Call call = HmdmInCallService.getCurrentCall();
        if (call != null) call.disconnect();
        finish();
    }

    private void toggleMute() {
        isMuted = !isMuted;
        HmdmInCallService service = HmdmInCallService.getInstance();
        if (service != null) service.muteCall(isMuted);
        muteBtn.setText(isMuted ? "UNMUTE" : "MUTE");
        muteBtn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(isMuted ? "#E65100" : "#1565C0")));
    }

    private void toggleSpeaker() {
        isSpeaker = !isSpeaker;
        HmdmInCallService service = HmdmInCallService.getInstance();
        if (service != null) service.setSpeakerRoute(isSpeaker);
        speakerBtn.setText(isSpeaker ? "SPKR ON" : "SPEAKER");
        speakerBtn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(isSpeaker ? "#1B5E20" : "#1565C0")));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
}