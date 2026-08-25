package com.example.jobboardauto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JobBoardAccessibilityService extends AccessibilityService {
    private static volatile JobBoardAccessibilityService instance;
    private static volatile boolean armed = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private long lastScan;
    private long lastAccepted;
    private boolean gestureInFlight;
    private static final Pattern PRICE = Pattern.compile("£\\s*([0-9]{1,4}(?:[,.][0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!armed || instance != JobBoardAccessibilityService.this) return;
            scanAndAct();
            handler.postDelayed(this, 80);
        }
    };

    public static void setArmed(boolean value) {
        armed = value;
        if (instance != null) instance.onArmedChanged(value);
    }

    private void onArmedChanged(boolean value) {
        handler.removeCallbacks(loop);
        if (value) handler.post(loop);
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        tts = new TextToSpeech(this, status -> { if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.UK); });
        if (armed) handler.post(loop);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // The 80 ms loop intentionally does not depend on event timing; events are used only as a hint.
        if (armed && event != null && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            // Scan immediately when the UI changes, while the periodic loop provides a fast fallback.
            if (System.currentTimeMillis() - lastScan > 20) scanAndAct();
        }
    }

    @Override public void onInterrupt() { setArmed(false); }

    private void scanAndAct() {
        if (!armed) return;
        long now = System.currentTimeMillis();
        if (now - lastScan < 15) return;
        lastScan = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            float threshold = getSharedPreferences("jobboard", MODE_PRIVATE).getFloat("threshold", 50f);
            Float found = findHighestPrice(root);
            if (found != null && found >= threshold && now - lastAccepted > 1200) {
                lastAccepted = now;
                if (clickAccept(root)) {
                    speak("İş kabul edildi");
                    return;
                }
            }
            scrollFast(root);
        } finally {
            root.recycle();
        }
    }

    private Float findHighestPrice(AccessibilityNodeInfo node) {
        float max = -1f;
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.remove(stack.size()-1);
            CharSequence t = n.getText();
            if (t != null) max = Math.max(max, parseMax(t.toString()));
            CharSequence d = n.getContentDescription();
            if (d != null) max = Math.max(max, parseMax(d.toString()));
            for (int i=0;i<n.getChildCount();i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.add(c);
            }
            if (n != node) n.recycle();
        }
        return max >= 0 ? max : null;
    }

    private float parseMax(String s) {
        Matcher m = PRICE.matcher(s);
        float max = -1f;
        while (m.find()) {
            try { max = Math.max(max, Float.parseFloat(m.group(1).replace(',', '.'))); }
            catch (Exception ignored) {}
        }
        return max;
    }

    private boolean clickAccept(AccessibilityNodeInfo root) {
        int[] size = new int[2];
        android.view.Display display = getDisplay();
        if (display != null) { size[0] = display.getWidth(); size[1] = display.getHeight(); }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectClickable(root, nodes);
        AccessibilityNodeInfo best = null;
        double bestScore = Double.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) { n.recycle(); continue; }
            float cx = r.centerX(), cy = r.centerY();
            // The sample screenshots place the blue accept/check control in the upper-right portion of the board.
            if (size[0] > 0 && (cx < size[0] * 0.72f || cy > size[1] * 0.45f)) { n.recycle(); continue; }
            if (r.width() > size[0]*0.25f || r.height() > size[1]*0.18f) { n.recycle(); continue; }
            double score = Math.abs(cx - size[0]*0.91) + Math.abs(cy - size[1]*0.22);
            CharSequence cd = n.getContentDescription();
            CharSequence tx = n.getText();
            String s = ((cd==null?"":cd.toString())+" "+(tx==null?"":tx.toString())).toLowerCase(Locale.UK);
            if (s.contains("accept") || s.contains("confirm") || s.contains("check") || s.contains("yes")) score -= 500;
            if (score < bestScore) { if (best != null) best.recycle(); best = n; bestScore = score; } else n.recycle();
        }
        if (best != null) {
            boolean ok = best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!ok && best.getParent() != null) ok = best.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            best.recycle();
            return ok;
        }
        return false;
    }

    private void collectClickable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n.isClickable()) out.add(AccessibilityNodeInfo.obtain(n));
        for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo c=n.getChild(i); if(c!=null){ collectClickable(c,out); c.recycle(); } }
    }

    private void scrollFast(AccessibilityNodeInfo root) {
        if (gestureInFlight) return;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            scrollable.recycle();
            if (ok) return;
        }
        android.view.Display d = getDisplay();
        if (d == null) return;
        android.graphics.Point p = new android.graphics.Point(); d.getRealSize(p);
        float x = p.x * 0.50f;
        float y1 = p.y * 0.70f, y2 = p.y * 0.30f;
        Path path = new Path(); path.moveTo(x,y1); path.lineTo(x,y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 55);
        gestureInFlight = true;
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){ gestureInFlight=false; }
            @Override public void onCancelled(GestureDescription g){ gestureInFlight=false; }
        }, null);
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n) {
        if (n.isScrollable()) return AccessibilityNodeInfo.obtain(n);
        for(int i=0;i<n.getChildCount();i++){ AccessibilityNodeInfo c=n.getChild(i); if(c!=null){ AccessibilityNodeInfo r=findScrollable(c); c.recycle(); if(r!=null)return r; } }
        return null;
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "job_accepted");
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) { tts.stop(); tts.shutdown(); tts=null; }
        instance=null;
        super.onDestroy();
    }
}
