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
    private long lastRefresh;
    private boolean gestureInFlight;
    private static final long REFRESH_INTERVAL_MS = 80;
    private static final Pattern PRICE = Pattern.compile("£\\s*([0-9]{1,4}(?:[,.][0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    private static class PriceMatch {
        float value = -1f;
        final Rect bounds = new Rect();
    }

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
        if (armed && event != null && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
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
            PriceMatch match = findHighestPrice(root);
            if (match.value >= threshold && match.value > 0 && now - lastAccepted > 1200) {
                lastAccepted = now;
                if (clickAccept(root, match.bounds)) {
                    speak("İş kabul edildi");
                    return;
                }
            }
            if (now - lastRefresh >= REFRESH_INTERVAL_MS) {
                lastRefresh = now;
                pullToRefresh();
            }
        } finally {
            root.recycle();
        }
    }

    private PriceMatch findHighestPrice(AccessibilityNodeInfo node) {
        PriceMatch result = new PriceMatch();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.remove(stack.size()-1);
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            float v1 = t != null ? parseMax(t.toString()) : -1f;
            float v2 = d != null ? parseMax(d.toString()) : -1f;
            float v = Math.max(v1, v2);
            if (v > result.value) {
                result.value = v;
                n.getBoundsInScreen(result.bounds);
            }
            for (int i=0;i<n.getChildCount();i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.add(c);
            }
            if (n != node) n.recycle();
        }
        return result;
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

    private boolean clickAccept(AccessibilityNodeInfo root, Rect priceBounds) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectClickable(root, nodes);
        AccessibilityNodeInfo best = null;
        double bestScore = Double.MAX_VALUE;
        int rowTolerance = Math.max(priceBounds.height(), 1) * 4;
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) { n.recycle(); continue; }
            // Only consider controls roughly on the same row as the highest price found.
            if (Math.abs(r.centerY() - priceBounds.centerY()) > rowTolerance) { n.recycle(); continue; }
            // Among same-row candidates, strongly prefer the rightmost one (the accept
            // checkmark sits to the right of the map icon in this layout).
            double score = Math.abs(r.centerY() - priceBounds.centerY()) * 2.0 - r.centerX();
            CharSequence cd = n.getContentDescription();
            CharSequence tx = n.getText();
            String s = ((cd==null?"":cd.toString())+" "+(tx==null?"":tx.toString())).toLowerCase(Locale.UK);
            if (s.contains("accept") || s.contains("confirm") || s.contains("check") || s.contains("yes")) score -= 100000;
            if (s.contains("map") || s.contains("route")) score += 100000;
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

    private void pullToRefresh() {
        if (gestureInFlight) return;
        android.view.Display d = getDisplay();
        if (d == null) return;
        android.graphics.Point p = new android.graphics.Point(); d.getRealSize(p);
        float x = p.x * 0.50f;
        float y1 = p.y * 0.25f, y2 = p.y * 0.75f;
        Path path = new Path(); path.moveTo(x,y1); path.lineTo(x,y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 120);
        gestureInFlight = true;
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){ gestureInFlight=false; }
            @Override public void onCancelled(GestureDescription g){ gestureInFlight=false; }
        }, null);
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
