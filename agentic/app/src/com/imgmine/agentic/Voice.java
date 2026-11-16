package com.imgmine.agentic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * EXPERIMENTAL push-to-talk voice input. Default OFF; mic permission is asked only when
 * the user enables it in Settings. Recognized text goes into the input box for review -
 * it is never auto-sent. Mic never stays on: listening stops after each phrase or tap.
 */
public final class Voice {
    public interface Cb {
        void onText(String text);   // partial or final recognized text - put it in the input box
        void onState(String hindiState); // short status / friendly error
        void onDone();              // listening stopped (button state reset)
    }

    public static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }
    public static void setEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("on", on).apply(); }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("agentic_voice", Context.MODE_PRIVATE); }
    public static boolean micGranted(Context c) {
        return c.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED;
    }

    private SpeechRecognizer sr;
    private boolean listening;

    public boolean isListening() { return listening; }

    public void toggle(Context c, Cb cb) {
        if (listening) { stop(cb); return; }
        if (!micGranted(c)) { cb.onState("Mic permission nahi hai - Settings me voice on karo."); cb.onDone(); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) { cb.onState("Is phone pe voice recognition available nahi hai."); cb.onDone(); return; }
        destroy();
        try { sr = SpeechRecognizer.createSpeechRecognizer(c); } catch (Throwable t) { cb.onState("Voice start nahi hua."); cb.onDone(); return; }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { cb.onState("Boliye... (Hindi/English/Hinglish)"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { cb.onState("Samajh raha hoon..."); }
            @Override public void onError(int e) { listening = false; cb.onState(err(e)); cb.onDone(); }
            @Override public void onResults(Bundle r) { listening = false; emit(r, cb); cb.onDone(); }
            @Override public void onPartialResults(Bundle r) { emit(r, cb); }
            @Override public void onEvent(int t, Bundle p) {}
        });
        try {
            sr.startListening(i);
            listening = true;
        } catch (Throwable t) { cb.onState("Voice start nahi hua."); cb.onDone(); }
    }

    private static void emit(Bundle r, Cb cb) {
        ArrayList<String> list = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty() && list.get(0) != null && !list.get(0).trim().isEmpty())
            cb.onText(list.get(0).trim());
    }

    public void stop(Cb cb) {
        listening = false;
        try { if (sr != null) sr.stopListening(); } catch (Throwable ignored) {}
        if (cb != null) { cb.onState("Sunna band."); cb.onDone(); }
    }

    public void destroy() {
        listening = false;
        try { if (sr != null) { sr.destroy(); sr = null; } } catch (Throwable ignored) {}
    }

    private static String err(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audio record nahi hua - dobara try karo.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Voice ke liye internet chahiye is phone pe.";
            case SpeechRecognizer.ERROR_NO_MATCH: return "Kuch samajh nahi aaya - dobara bolo.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "Kuch sunai nahi diya - mic ke paas bolo.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Mic permission nahi hai - Settings me voice on karo.";
            case SpeechRecognizer.ERROR_CLIENT: return "Voice abhi busy hai - dobara try karo.";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "Bahut jaldi-jaldi try ho raha - thoda ruko.";
            default: return "Voice error - dobara try karo.";
        }
    }
}
