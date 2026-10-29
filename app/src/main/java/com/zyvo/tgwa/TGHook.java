package com.zyvo.tgwa;

import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * TGWA — own-device WebView instrumentation for Telegram mini apps.
 *
 * Purpose: Telegram refuses a user/system MITM CA inside its WebView, so the
 * mini app's requests cannot be captured on the wire. Instead we open the
 * Chromium DevTools socket for Telegram's WebViews and log every URL it loads,
 * so `Telegram.WebApp.initData` can be read over CDP from Termux.
 */
public class TGHook implements IXposedHookLoadPackage {

    private static final String TAG = "TGWA";
    private static final String FILE = "/data/local/tmp/tgwa.log";

    static synchronized void log(String s) {
        try {
            File f = new File(FILE);
            FileWriter w = new FileWriter(f, true);
            w.write("[" + System.currentTimeMillis() + "] " + s + "\n");
            w.close();
            if (f.length() > 512 * 1024) { f.delete(); }
        } catch (Throwable ignored) { }
        try { XposedBridge.log(TAG + ": " + s); } catch (Throwable ignored) { }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        String p = lp.packageName;
        if (p == null || !p.equals("org.telegram.messenger")) return;

        log("hook loaded proc=" + lp.processName);

        // 1) turn devtools on right now (covers views created later)
        try {
            WebView.setWebContentsDebuggingEnabled(true);
            log("setWebContentsDebuggingEnabled(true) direct OK");
        } catch (Throwable t) { log("direct call failed: " + t); }

        // 2) force it true for every future call too
        try {
            XposedBridge.hookAllMethods(WebView.class, "setWebContentsDebuggingEnabled",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length == 1) {
                            param.args[0] = Boolean.TRUE;
                        }
                    }
                });
            log("hook setWebContentsDebuggingEnabled");
        } catch (Throwable t) { log("hook debug-flag failed: " + t); }

        // 3) log every URL a WebView loads (may carry tgWebAppData)
        try {
            XposedBridge.hookAllMethods(WebView.class, "loadUrl", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        log("loadUrl " + param.args[0]);
                    }
                }
            });
            log("hook loadUrl");
        } catch (Throwable t) { log("hook loadUrl failed: " + t); }

        try {
            XposedBridge.hookAllMethods(WebView.class, "loadDataWithBaseURL", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("loadDataWithBaseURL");
                    for (int i = 0; i < param.args.length; i++) {
                        String v = String.valueOf(param.args[i]);
                        if (v.length() > 300) v = v.substring(0, 300) + "...(" + v.length() + ")";
                        sb.append(" a").append(i).append("=").append(v);
                    }
                    log(sb.toString());
                }
            });
            log("hook loadDataWithBaseURL");
        } catch (Throwable t) { log("hook loadData failed: " + t); }

        // 4) log evaluateJavascript payloads (Telegram injects the bridge object)
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "evaluateJavascript",
                String.class, ValueCallback.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        String js = String.valueOf(param.args[0]);
                        if (js.length() > 400) js = js.substring(0, 400) + "...(" + js.length() + ")";
                        log("evaluateJavascript " + js.replace('\n', ' '));
                    }
                });
            log("hook evaluateJavascript");
        } catch (Throwable t) { log("hook evaluateJavascript failed: " + t); }

        // 5) re-arm whenever a new WebView is constructed
        try {
            XposedHelpers.findAndHookConstructor(WebView.class,
                android.content.Context.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            WebView.setWebContentsDebuggingEnabled(true);
                            log("WebView ctor -> debug re-armed");
                        } catch (Throwable ignored) { }
                    }
                });
        } catch (Throwable t) { log("hook ctor failed: " + t); }

        log("armed");
    }
}
