package com.threadstock.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * ThreadStock: the whole app lives in assets/index.html and runs offline inside a WebView.
 * This class only adds the things a web page cannot do by itself:
 *   - choosing photos / backup files (file chooser)
 *   - saving PDFs, images and backups into Downloads/ThreadStock
 *   - printing a receipt through Android's print dialog (printer or "Save as PDF")
 *   - unlocking with a fingerprint (the phone does the matching)
 *   - sending a backup to Google Drive through the share sheet
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_PICK = 1;
    private static final String DRIVE_PKG = "com.google.android.apps.docs";

    private FrameLayout root;
    private WebView web;
    private WebView printView; // kept alive while the print dialog is open
    private ValueCallback<Uri[]> pickCallback;
    private int barsBg = Color.parseColor("#F2F6F6");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        root = new FrameLayout(this);
        root.setBackgroundColor(barsBg);

        web = new WebView(this);
        web.setBackgroundColor(barsBg);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        if (Build.VERSION.SDK_INT < 33) {
            //noinspection deprecation
            s.setForceDark(WebSettings.FORCE_DARK_OFF); // the page has its own dark theme; do not darken it twice
        } else {
            s.setAlgorithmicDarkeningAllowed(false);
        }

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { // WhatsApp links and anything else open outside the app
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_PICK);
                } catch (Exception e) {
                    pickCallback = null;
                    return false;
                }
                return true;
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        setupEdgeToEdge();
        setupBack();
        applyBars(barsBg, true);
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    /**
     * Android 15+ always draws apps edge to edge (and so does this app on Android 11+ so it looks the same
     * everywhere). The page is pushed in by the height of the status bar, navigation bar and keyboard.
     */
    private void setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 30) return; // older phones keep the normal system-bar layout
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
        root.requestApplyInsets();
    }

    /** Background behind the system bars and the colour of their icons, so dark mode looks right. */
    private void applyBars(final int bg, final boolean darkIcons) {
        barsBg = bg;
        root.setBackgroundColor(bg);
        if (web != null) web.setBackgroundColor(bg);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(darkIcons ? flags : 0, flags);
            }
        } else {
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(darkIcons ? (f | light) : (f & ~light));
        }
    }

    /** Android 13+ asks apps to register for the back gesture; older phones still call onBackPressed(). */
    private void setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                        @Override
                        public void onBackInvoked() { handleBack(); }
                    });
        }
    }

    private void handleBack() {
        web.evaluateJavascript("(function(){return window.appBack ? window.appBack() : false;})()", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK) {
            if (pickCallback != null) {
                pickCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pickCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private void printHtml(String html, final String title) {
        printView = new WebView(this);
        printView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) return;
                PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(title);
                pm.print(title, adapter, null);
            }
        });
        printView.loadDataWithBaseURL("https://" + HOST + "/", html, "text/html", "UTF-8", null);
    }

    private void tellPage(final String js) {
        web.post(() -> web.evaluateJavascript(js, null));
    }

    /** Called from the page as window.AndroidBridge.* */
    public class Bridge {

        /* ---------------- files and printing ---------------- */

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ThreadStock");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("no uri");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("no stream");
                    os.write(bytes);
                }
                toast("Saved in Downloads/ThreadStock: " + name);
            } catch (Exception e) {
                toast("Could not save the file");
            }
        }

        @JavascriptInterface
        public void print(final String html, final String title) {
            runOnUiThread(() -> printHtml(html, title));
        }

        /* ---------------- Google Drive (and any other app) ---------------- */

        /**
         * Hands a file to another app through the share sheet. With preferDrive the Google Drive
         * "Save to Drive" screen opens straight away; if Drive is not installed the normal share sheet opens.
         * Returns true when the file was prepared and the screen was asked to open.
         */
        @JavascriptInterface
        public boolean shareFile(String name, final String mime, String base64, final String title, final boolean preferDrive) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "share");
                if (dir.exists()) {
                    File[] old = dir.listFiles();
                    if (old != null) {
                        for (File f : old) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                } else if (!dir.mkdirs()) {
                    return false;
                }
                File out = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", out);
                runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, title);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        if (preferDrive) {
                            Intent drive = new Intent(send);
                            drive.setPackage(DRIVE_PKG);
                            startActivity(drive);
                            return;
                        }
                    } catch (ActivityNotFoundException | SecurityException ignored) {
                        // Drive is not installed: fall through to the normal share sheet
                    }
                    try {
                        startActivity(Intent.createChooser(send, title));
                    } catch (Exception e) {
                        toast("Could not open the share screen");
                    }
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /* ---------------- fingerprint ---------------- */

        @JavascriptInterface
        public boolean biometricAvailable() {
            try {
                BiometricManager bm = (BiometricManager) getSystemService(Context.BIOMETRIC_SERVICE);
                if (bm == null) return false;
                if (Build.VERSION.SDK_INT >= 30) {
                    return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                            == BiometricManager.BIOMETRIC_SUCCESS;
                }
                return bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            } catch (Throwable t) {
                return false;
            }
        }

        /** Shows the system fingerprint prompt, then calls window.onBiometric(true|false) in the page. */
        @JavascriptInterface
        public void biometricAuth(final String title, final String negativeText) {
            runOnUiThread(() -> {
                final boolean[] done = {false};
                try {
                    BiometricPrompt.Builder b = new BiometricPrompt.Builder(MainActivity.this)
                            .setTitle(title)
                            .setNegativeButton(negativeText, getMainExecutor(), (dialog, which) -> {
                                if (!done[0]) { done[0] = true; tellPage("window.onBiometric&&window.onBiometric(false)"); }
                            });
                    b.setConfirmationRequired(false);
                    if (Build.VERSION.SDK_INT >= 30) {
                        b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                    }
                    BiometricPrompt prompt = b.build();
                    prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            if (!done[0]) { done[0] = true; tellPage("window.onBiometric&&window.onBiometric(true)"); }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (!done[0]) { done[0] = true; tellPage("window.onBiometric&&window.onBiometric(false)"); }
                        }
                    });
                } catch (Throwable t) {
                    if (!done[0]) { done[0] = true; tellPage("window.onBiometric&&window.onBiometric(false)"); }
                }
            });
        }

        /* ---------------- notifications ---------------- */

        /** Shows a notification in the phone's notification bar (the page uses it when the app is in the background). */
        @JavascriptInterface
        public void notifyUser(final String title, final String body) {
            Notifier.post(MainActivity.this, (int) (System.currentTimeMillis() % 100000) + 100, title, body);
        }

        /** Asks for the notification permission on Android 13+. Returns true when it is already allowed. */
        @JavascriptInterface
        public boolean notifPermission() {
            if (Notifier.allowed(MainActivity.this)) return true;
            runOnUiThread(() -> {
                try {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
                } catch (Exception ignored) { }
            });
            return false;
        }

        /** Gives the morning alarm its list: {on, due, sup, hour, cur, c:[{n,a,d}], s:[{n,a}], tx:{...}}. */
        @JavascriptInterface
        public void setReminders(String json) {
            Notifier.save(MainActivity.this, json);
        }

        /* ---------------- look ---------------- */

        /** The page tells the phone its background colour (e.g. "#F2F6F6") and whether it is a light theme. */
        @JavascriptInterface
        public void setBars(final String color, final boolean lightTheme) {
            runOnUiThread(() -> {
                try {
                    applyBars(Color.parseColor(color), lightTheme);
                } catch (IllegalArgumentException ignored) { }
            });
        }
    }
}
