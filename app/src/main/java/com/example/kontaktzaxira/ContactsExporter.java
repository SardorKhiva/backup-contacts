package com.example.kontaktzaxira;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Telefondagi kontaktlarni foydalanuvchi tanlagan .vcf faylga (vCard 3.0, UTF-8) yozadi. */
final class ContactsExporter {

    static final class Result {
        int count;
        boolean cancelled;
    }

    private ContactsExporter() {
    }

    static Result export(ContentResolver cr, Uri target, boolean withPhotos,
                         Job.Progress progress, Job.Cancel cancel) throws IOException {
        final Result result = new Result();
        final int total = ContactsReader.countContacts(cr);
        progress.onProgress(0, total);

        OutputStream os = openOutput(cr, target);
        try (Writer out = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 64 * 1024)) {
            ContactsReader.readAll(cr, withPhotos, contact -> {
                if (cancel.isCancelled()) return false;
                VCardWriter.write(out, contact, withPhotos);
                result.count++;
                progress.onProgress(result.count, Math.max(total, result.count));
                return true;
            });
            out.flush();
        }
        result.cancelled = cancel.isCancelled();
        progress.onProgress(result.count, Math.max(total, result.count));
        return result;
    }

    /** "wt" — mavjud faylni qisqartirib yozadi; ba'zi provayderlar buni bilmaydi, shuning uchun zaxira yo'l bor. */
    private static OutputStream openOutput(ContentResolver cr, Uri uri) throws IOException {
        OutputStream os = null;
        try {
            os = cr.openOutputStream(uri, "wt");
        } catch (Exception ignored) {
            // oddiy rejimda qayta uriniladi
        }
        if (os == null) os = cr.openOutputStream(uri);
        if (os == null) throw new IOException("Cannot open output file");
        return os;
    }
}
