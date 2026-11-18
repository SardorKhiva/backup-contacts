package com.example.kontaktzaxira;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Yagona ekran. Orqa fonda hech narsa ishlamaydi: Service, Receiver, WorkManager yo'q.
 * Ish faqat ekran ochiq turganda, bitta fon oqimda bajariladi va tugagach oqim to'xtaydi.
 */
public class MainActivity extends Activity {

    private static final int RC_PERM_EXPORT = 1;
    private static final int RC_PERM_IMPORT = 2;
    private static final int RC_SAVE_FILE = 11;
    private static final int RC_OPEN_FILE = 12;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * Fon oqim va UI oqimi o'rtasida: bekor qilish so'rovi.
     */
    private volatile boolean cancelRequested;
    /**
     * Faqat UI oqimida o'qiladi/yoziladi.
     */
    private boolean busy;
    /**
     * Faqat fon oqimda o'qiladi/yoziladi (progress'ni siyraklashtirish uchun).
     */
    private long lastProgressPost;

    private Uri lastExportUri;
    private Configuration lastConfig;

    private ScrollView scroll;
    private Button btnExport;
    private Button btnImport;
    private Button btnCancel;
    private Button btnShare;
    private Switch swPhotos;
    private View panelProgress;
    private View panelResult;
    private TextView txtStatus;
    private TextView txtCount;
    private TextView txtResultTitle;
    private TextView txtResultInfo;
    private ProgressBar bar;

    // ------------------------------------------------------------------ hayot sikli

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        lastConfig = new Configuration(getResources().getConfiguration());

        scroll = findViewById(R.id.scroll);
        btnExport = findViewById(R.id.btnExport);
        btnImport = findViewById(R.id.btnImport);
        btnCancel = findViewById(R.id.btnCancel);
        btnShare = findViewById(R.id.btnShare);
        swPhotos = findViewById(R.id.swPhotos);
        panelProgress = findViewById(R.id.panelProgress);
        panelResult = findViewById(R.id.panelResult);
        txtStatus = findViewById(R.id.txtStatus);
        txtCount = findViewById(R.id.txtCount);
        txtResultTitle = findViewById(R.id.txtResultTitle);
        txtResultInfo = findViewById(R.id.txtResultInfo);
        bar = findViewById(R.id.bar);

        btnExport.setOnClickListener(v -> onExportClicked());
        btnImport.setOnClickListener(v -> onImportClicked());
        btnCancel.setOnClickListener(v -> requestCancel());
        btnShare.setOnClickListener(v -> shareFile());
    }

    @Override
    protected void onDestroy() {
        cancelRequested = true; // ekran yopilsa, ish ham to'xtaydi
        super.onDestroy();
    }

    /**
     * Manifestda aylantirish va shu kabi o'zgarishlar uchun ekran qayta yaratilmasligi
     * belgilangan (jarayon uzilib qolmasligi uchun). Mavzu yoki til o'zgarsa va hozir
     * jarayon yo'q bo'lsa, ekranni yangilaymiz.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int changes = newConfig.diff(lastConfig);
        lastConfig = new Configuration(newConfig);
        int visual = ActivityInfo.CONFIG_UI_MODE | ActivityInfo.CONFIG_LOCALE
                | ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_DENSITY
                | ActivityInfo.CONFIG_LAYOUT_DIRECTION;
        if (!busy && (changes & visual) != 0) {
            recreate();
        }
    }

    // ------------------------------------------------------------------ ruxsatlar

    private boolean granted(String permission) {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions(int requestCode, String... permissions) {
        // Android 5.x da ruxsatlar o'rnatishda beriladi, so'rash kerak emas
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(permissions, requestCode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            showPermissionDialog();
            return;
        }
        if (requestCode == RC_PERM_EXPORT) {
            pickSaveFile();
        } else if (requestCode == RC_PERM_IMPORT) {
            pickOpenFile();
        }
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(R.string.perm_message)
                .setPositiveButton(R.string.btn_settings, (d, which) -> openAppSettings())
                .setNegativeButton(R.string.btn_close, null)
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // sozlamalar ochilmadi — foydalanuvchi qo'lda ochadi
        }
    }

    // ------------------------------------------------------------------ tugmalar va fayl tanlash

    private void onExportClicked() {
        if (busy) return;
        if (granted(Manifest.permission.READ_CONTACTS)) {
            pickSaveFile();
        } else {
            askPermissions(RC_PERM_EXPORT, Manifest.permission.READ_CONTACTS);
        }
    }

    private void onImportClicked() {
        if (busy) return;
        if (granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.WRITE_CONTACTS)) {
            pickOpenFile();
        } else {
            askPermissions(RC_PERM_IMPORT,
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
        }
    }

    /**
     * Tizimning "faylni saqlash" oynasi: xotira ruxsati kerak emas.
     */
    private void pickSaveFile() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/x-vcard");
        intent.putExtra(Intent.EXTRA_TITLE, "kontaktlar_" + stamp + ".vcf");
        startPicker(intent, RC_SAVE_FILE);
    }

    /**
     * Tizimning "faylni tanlash" oynasi.
     */
    private void pickOpenFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/x-vcard", "text/vcard", "text/directory", "text/plain",
                "application/octet-stream"});
        startPicker(intent, RC_OPEN_FILE);
    }

    private void startPicker(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.err_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == RC_SAVE_FILE) {
            startExport(uri);
        } else if (requestCode == RC_OPEN_FILE) {
            prepareImport(uri);
        }
    }

    // ------------------------------------------------------------------ saqlash (export)

    private void startExport(final Uri uri) {
        final boolean photos = swPhotos.isChecked();
        showRunning(R.string.status_exporting, true);
        startJob(() -> {
            try {
                ContactsExporter.Result result = ContactsExporter.export(
                        getContentResolver(), uri, photos, this::postProgress, () -> cancelRequested);
                if (result.cancelled || result.count == 0) {
                    deleteQuietly(uri); // bo'sh yoki chala fayl qolmasin
                }
                postUi(() -> onExportFinished(result, uri));
            } catch (Throwable t) {
                deleteQuietly(uri);
                postUi(() -> onJobFailed(t));
            }
        });
    }

    private void onExportFinished(ContactsExporter.Result result, Uri uri) {
        if (result.cancelled) {
            showResult(false, getString(R.string.result_cancelled), null, null);
        } else if (result.count == 0) {
            showResult(false, getString(R.string.result_error),
                    getString(R.string.result_no_contacts), null);
        } else {
            String name = fileName(uri);
            showResult(true,
                    getString(R.string.result_export_ok, number(result.count)),
                    name == null ? null : getString(R.string.result_export_file, name),
                    uri);
        }
    }

    // ------------------------------------------------------------------ tiklash (import)

    /**
     * Avval qaysi hisoblarga yozish mumkinligini aniqlaymiz; bittagina bo'lsa, so'ramaymiz.
     */
    private void prepareImport(final Uri file) {
        showRunning(R.string.status_checking, false);
        startJob(() -> {
            try {
                List<ContactsImporter.Account> accounts = ContactsImporter.listAccounts(this);
                postUi(() -> {
                    if (accounts.size() == 1) {
                        startImport(file, accounts.get(0));
                    } else {
                        showAccountDialog(file, accounts);
                    }
                });
            } catch (Throwable t) {
                postUi(() -> onJobFailed(t));
            }
        });
    }

    private void showAccountDialog(final Uri file, final List<ContactsImporter.Account> accounts) {
        String[] labels = new String[accounts.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = accounts.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_account_title)
                .setItems(labels, (d, which) -> startImport(file, accounts.get(which)))
                .setNegativeButton(R.string.btn_cancel, (d, which) -> showIdle())
                .setOnCancelListener(d -> showIdle())
                .show();
    }

    private void startImport(final Uri file, final ContactsImporter.Account account) {
        showRunning(R.string.status_importing, true);
        startJob(() -> {
            try {
                ContactsImporter.Result result = new ContactsImporter(getContentResolver()).run(
                        file, account.type, account.name, this::postProgress, () -> cancelRequested);
                postUi(() -> onImportFinished(result));
            } catch (Throwable t) {
                postUi(() -> onJobFailed(t));
            }
        });
    }

    private void onImportFinished(ContactsImporter.Result result) {
        if (result.total == 0) {
            showResult(false, getString(R.string.result_error),
                    getString(R.string.result_no_cards), null);
        } else if (result.cancelled) {
            showResult(false, getString(R.string.result_cancelled),
                    getString(R.string.result_cancelled_import, number(result.added)), null);
        } else {
            String info = (result.skipped > 0 || result.failed > 0)
                    ? getString(R.string.result_import_details,
                    number(result.skipped), number(result.failed))
                    : null;
            showResult(result.added > 0 || result.failed == 0,
                    getString(R.string.result_import_ok, number(result.added)), info, null);
        }
    }

    // ------------------------------------------------------------------ umumiy jarayon

    /**
     * Bitta fon oqim. Ish tugagach oqim o'zi tugaydi, orqa fonda hech narsa qolmaydi.
     */
    private void startJob(Runnable job) {
        cancelRequested = false;
        new Thread(job, "contacts-job").start();
    }

    private void requestCancel() {
        cancelRequested = true;
        btnCancel.setEnabled(false);
        txtStatus.setText(R.string.status_cancelling);
    }

    /**
     * Fon oqimdan chaqiriladi; UI'ni sekundiga ~8 martadan ko'p yangilamaydi.
     */
    private void postProgress(int done, int total) {
        long now = SystemClock.uptimeMillis();
        if (done < total && now - lastProgressPost < 120) return;
        lastProgressPost = now;
        postUi(() -> updateProgress(done, total));
    }

    private void postUi(Runnable action) {
        ui.post(() -> {
            if (!isDestroyed()) action.run();
        });
    }

    private void onJobFailed(Throwable t) {
        if (t instanceof SecurityException) {
            showIdle();
            showPermissionDialog();
            return;
        }
        String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        showResult(false, getString(R.string.result_error),
                getString(R.string.result_error_hint) + "\n\n" + detail, null);
    }

    // ------------------------------------------------------------------ ekran holatlari

    private void showIdle() {
        busy = false;
        panelProgress.setVisibility(View.GONE);
        setControlsEnabled(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showRunning(int statusRes, boolean cancelable) {
        busy = true;
        panelResult.setVisibility(View.GONE);
        btnShare.setVisibility(View.GONE);
        panelProgress.setVisibility(View.VISIBLE);
        txtStatus.setText(statusRes);
        txtCount.setText("");
        bar.setIndeterminate(true);
        btnCancel.setVisibility(cancelable ? View.VISIBLE : View.GONE);
        btnCancel.setEnabled(cancelable);
        setControlsEnabled(false);
        // Jarayon davomida ekran o'chib qolmasin (ish ilova ochiq turganda bajariladi)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scrollToBottom();
    }

    private void showResult(boolean ok, String title, String info, Uri shareUri) {
        showIdle();
        lastExportUri = shareUri;
        txtResultTitle.setText(title);
        txtResultTitle.setTextColor(color(ok ? R.color.success : R.color.error));
        txtResultInfo.setText(info == null ? "" : info);
        txtResultInfo.setVisibility(info == null ? View.GONE : View.VISIBLE);
        btnShare.setVisibility(shareUri == null ? View.GONE : View.VISIBLE);
        panelResult.setVisibility(View.VISIBLE);
        scrollToBottom();
    }

    private void updateProgress(int done, int total) {
        if (total > 0) {
            bar.setIndeterminate(false);
            bar.setMax(total);
            bar.setProgress(Math.min(done, total));
            txtCount.setText(getString(R.string.count_format, number(done), number(total)));
        } else {
            bar.setIndeterminate(true);
            txtCount.setText(number(done));
        }
    }

    private void setControlsEnabled(boolean enabled) {
        btnExport.setEnabled(enabled);
        btnImport.setEnabled(enabled);
        swPhotos.setEnabled(enabled);
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------------------ yordamchilar

    private void shareFile() {
        if (lastExportUri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/x-vcard");
        send.putExtra(Intent.EXTRA_STREAM, lastExportUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_title)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.err_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void deleteQuietly(Uri uri) {
        try {
            DocumentsContract.deleteDocument(getContentResolver(), uri);
        } catch (Exception ignored) {
            // fayl o'chmasa, foydalanuvchi qo'lda o'chiradi
        }
    }

    private String fileName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
            // nom ko'rsatilmaydi
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static String number(int n) {
        return NumberFormat.getIntegerInstance().format(n);
    }

    @SuppressWarnings("deprecation")
    private int color(int resId) {
        return Build.VERSION.SDK_INT >= 23 ? getColor(resId) : getResources().getColor(resId);
    }
}
