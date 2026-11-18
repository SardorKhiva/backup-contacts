package com.example.kontaktzaxira;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * .vcf fayldagi kontaktlarni telefon kontakt bazasiga qo'shadi.
 * Fayl oqim bilan o'qiladi, kontaktlar 400 tagacha amal (taxminan 50-80 kontakt)
 * lik paketlar bilan yoziladi. Telefonda allaqachon bor kontaktlar (ism + raqam/email
 * bir xil) qayta qo'shilmaydi.
 */
final class ContactsImporter {

    /** Kontakt qo'shiladigan hisob. type == null — hisobsiz, faqat shu telefonda saqlanadi. */
    static final class Account {
        final String type;
        final String name;
        final String label;

        Account(String type, String name, String label) {
            this.type = type;
            this.name = name;
            this.label = label;
        }
    }

    static final class Result {
        int total;      // fayldagi kontaktlar soni
        int added;      // qo'shildi
        int skipped;    // telefonda avvaldan bor edi
        int failed;     // qo'shib bo'lmadi
        boolean cancelled;
    }

    private static final int MAX_OPS_PER_BATCH = 400;
    private static final int MAX_BYTES_PER_BATCH = 450 * 1024;

    /** Bu hisoblarga kontakt qo'shib bo'lmaydi yoki ma'nosiz (xabarlashish ilovalari). */
    private static final String[] EXCLUDED_ACCOUNT_PREFIXES = {
            "com.whatsapp", "org.telegram", "org.thoughtcrime", "com.viber", "com.skype",
            "com.facebook", "com.linkedin", "com.twitter", "com.instagram", "com.snapchat",
            "com.tencent", "jp.naver", "com.google.android.apps.tachyon"
    };

    private final ContentResolver cr;

    ContactsImporter(ContentResolver cr) {
        this.cr = cr;
    }

    // ------------------------------------------------------------------ hisoblar

    /**
     * Kontakt qo'shish mumkin bo'lgan hisoblar ro'yxati. Birinchisi doim "faqat shu telefonda".
     * Hisoblar kontakt bazasidagi mavjud yozuvlardan olinadi (Android 8+ da ilovalar
     * hisoblar ro'yxatini bevosita o'qiy olmaydi).
     */
    static List<Account> listAccounts(Context ctx) {
        ContentResolver resolver = ctx.getContentResolver();
        LinkedHashMap<String, Account> map = new LinkedHashMap<>();
        map.put("", new Account(null, null, ctx.getString(R.string.account_local)));

        collect(ctx, resolver, RawContacts.CONTENT_URI, RawContacts.ACCOUNT_TYPE,
                RawContacts.ACCOUNT_NAME, RawContacts.DELETED + "=0", map);
        collect(ctx, resolver, Groups.CONTENT_URI, Groups.ACCOUNT_TYPE,
                Groups.ACCOUNT_NAME, Groups.DELETED + "=0", map);
        collect(ctx, resolver, Settings.CONTENT_URI, Settings.ACCOUNT_TYPE,
                Settings.ACCOUNT_NAME, null, map);
        return new ArrayList<>(map.values());
    }

    private static void collect(Context ctx, ContentResolver resolver, Uri uri, String typeColumn,
                                String nameColumn, String selection, Map<String, Account> out) {
        Cursor c = null;
        try {
            c = resolver.query(uri, new String[]{typeColumn, nameColumn}, selection, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String type = c.getString(0);
                String name = c.getString(1);
                if (type == null || name == null || type.isEmpty() || name.isEmpty()) continue;
                if (isExcluded(type)) continue;
                String key = type + "\u0001" + name;
                if (!out.containsKey(key)) {
                    out.put(key, new Account(type, name, label(ctx, type, name)));
                }
            }
        } catch (RuntimeException ignored) {
            // ba'zi telefonlarda jadval yopiq bo'lishi mumkin — shunchaki o'tkazib yuboramiz
        } finally {
            if (c != null) c.close();
        }
    }

    private static boolean isExcluded(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        for (String prefix : EXCLUDED_ACCOUNT_PREFIXES) {
            if (t.startsWith(prefix)) return true;
        }
        return t.matches(".*\\.sim\\d*"); // SIM kartaga barcha maydonlarni yozib bo'lmaydi
    }

    private static String label(Context ctx, String type, String name) {
        if ("com.google".equals(type)) {
            return ctx.getString(R.string.account_google, name);
        }
        if ("vnd.sec.contact.phone".equals(type)) {
            return ctx.getString(R.string.account_device);
        }
        return ctx.getString(R.string.account_other, name, type);
    }

    // ------------------------------------------------------------------ import

    Result run(Uri file, String accountType, String accountName,
               Job.Progress progress, Job.Cancel cancel) throws IOException {
        final Result result = new Result();

        // 1) Fayldagi kontaktlar sonini aniqlaymiz (progress uchun)
        InputStream in = open(file);
        try {
            result.total = VCardParser.countCards(in);
        } finally {
            in.close();
        }
        if (result.total == 0) return result;
        progress.onProgress(0, result.total);

        // 2) Telefondagi mavjud kontaktlarning "imzolari" (takrorlanmaslik uchun)
        final HashSet<String> known = new HashSet<>();
        ContactsReader.readAll(cr, false, existing -> {
            known.addAll(signature(existing));
            return !cancel.isCancelled();
        });
        if (cancel.isCancelled()) {
            result.cancelled = true;
            return result;
        }

        // 3) Faylni oqim bilan o'qib, paketlab yozamiz
        final Batch batch = new Batch(result, accountType, accountName);
        final int[] seen = {0};
        in = open(file);
        try {
            VCardParser.parse(in, contact -> {
                if (cancel.isCancelled()) return false;
                seen[0]++;
                ArrayList<String> sig = signature(contact);
                if (known.containsAll(sig)) {
                    result.skipped++;
                } else {
                    known.addAll(sig);
                    batch.add(contact);
                }
                progress.onProgress(seen[0], result.total);
                return true;
            });
            batch.flush();
        } finally {
            in.close();
        }

        result.cancelled = cancel.isCancelled();
        if (!result.cancelled) progress.onProgress(result.total, result.total);
        return result;
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("Cannot open input file");
        return in;
    }

    /** Bir nechta kontaktni bitta applyBatch chaqiruviga yig'adi. */
    private final class Batch {
        private final Result result;
        private final String accountType;
        private final String accountName;
        private final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        private final ArrayList<ContactData> pending = new ArrayList<>();
        private int bytes;

        Batch(Result result, String accountType, String accountName) {
            this.result = result;
            this.accountType = accountType;
            this.accountName = accountName;
        }

        void add(ContactData c) {
            int estimate = estimateBytes(c);
            if (!pending.isEmpty()
                    && (ops.size() >= MAX_OPS_PER_BATCH || bytes + estimate > MAX_BYTES_PER_BATCH)) {
                flush();
            }
            buildOps(c, ops, accountType, accountName);
            pending.add(c);
            bytes += estimate;
        }

        void flush() {
            if (pending.isEmpty()) return;
            try {
                cr.applyBatch(ContactsContract.AUTHORITY, ops);
                result.added += pending.size();
            } catch (SecurityException e) {
                throw e; // ruxsat olib qo'yilgan — davom etishning ma'nosi yo'q
            } catch (Exception batchError) {
                // Paketdagi bitta buzuq kontakt hammasini to'xtatmasligi uchun birma-bir qayta uriniladi.
                // Provayder paketni to'liq bekor qiladi, shuning uchun takrorlanish bo'lmaydi.
                for (ContactData c : pending) {
                    ArrayList<ContentProviderOperation> single = new ArrayList<>();
                    buildOps(c, single, accountType, accountName);
                    try {
                        cr.applyBatch(ContactsContract.AUTHORITY, single);
                        result.added++;
                    } catch (SecurityException e) {
                        throw e;
                    } catch (Exception e) {
                        result.failed++;
                    }
                }
            } finally {
                ops.clear();
                pending.clear();
                bytes = 0;
            }
        }
    }

    // ------------------------------------------------------------------ amallarni qurish

    private static ContentProviderOperation.Builder data(int rawIndex, String mimeType) {
        return ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, rawIndex)
                .withValue(Data.MIMETYPE, mimeType);
    }

    private static void buildOps(ContactData c, List<ContentProviderOperation> ops,
                                 String accountType, String accountName) {
        final int raw = ops.size();
        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(RawContacts.ACCOUNT_NAME, accountName)
                .build());

        if (c.hasName()) {
            ContentProviderOperation.Builder b = data(raw, StructuredName.CONTENT_ITEM_TYPE);
            if (!c.fullName.isEmpty()) b.withValue(StructuredName.DISPLAY_NAME, c.fullName);
            if (!c.given.isEmpty()) b.withValue(StructuredName.GIVEN_NAME, c.given);
            if (!c.family.isEmpty()) b.withValue(StructuredName.FAMILY_NAME, c.family);
            if (!c.middle.isEmpty()) b.withValue(StructuredName.MIDDLE_NAME, c.middle);
            if (!c.prefix.isEmpty()) b.withValue(StructuredName.PREFIX, c.prefix);
            if (!c.suffix.isEmpty()) b.withValue(StructuredName.SUFFIX, c.suffix);
            ops.add(b.build());
        }

        if (!c.nickname.isEmpty()) {
            ops.add(data(raw, Nickname.CONTENT_ITEM_TYPE)
                    .withValue(Nickname.NAME, c.nickname)
                    .withValue(Nickname.TYPE, Nickname.TYPE_DEFAULT)
                    .build());
        }

        for (ContactData.Item p : c.phones) {
            ContentProviderOperation.Builder b = data(raw, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, p.value)
                    .withValue(Phone.TYPE, p.type);
            if (p.type == Phone.TYPE_CUSTOM) b.withValue(Phone.LABEL, p.label);
            ops.add(b.build());
        }

        for (ContactData.Item e : c.emails) {
            ContentProviderOperation.Builder b = data(raw, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, e.value)
                    .withValue(Email.TYPE, e.type);
            if (e.type == Email.TYPE_CUSTOM) b.withValue(Email.LABEL, e.label);
            ops.add(b.build());
        }

        for (ContactData.Address a : c.addresses) {
            ContentProviderOperation.Builder b = data(raw, StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(StructuredPostal.TYPE, a.type);
            if (a.type == StructuredPostal.TYPE_CUSTOM) b.withValue(StructuredPostal.LABEL, a.label);
            if (!a.street.isEmpty()) b.withValue(StructuredPostal.STREET, a.street);
            if (!a.pobox.isEmpty()) b.withValue(StructuredPostal.POBOX, a.pobox);
            if (!a.extended.isEmpty()) b.withValue(StructuredPostal.NEIGHBORHOOD, a.extended);
            if (!a.city.isEmpty()) b.withValue(StructuredPostal.CITY, a.city);
            if (!a.region.isEmpty()) b.withValue(StructuredPostal.REGION, a.region);
            if (!a.postcode.isEmpty()) b.withValue(StructuredPostal.POSTCODE, a.postcode);
            if (!a.country.isEmpty()) b.withValue(StructuredPostal.COUNTRY, a.country);
            ops.add(b.build());
        }

        if (!c.company.isEmpty() || !c.department.isEmpty() || !c.title.isEmpty()) {
            ContentProviderOperation.Builder b = data(raw, Organization.CONTENT_ITEM_TYPE)
                    .withValue(Organization.TYPE, Organization.TYPE_WORK);
            if (!c.company.isEmpty()) b.withValue(Organization.COMPANY, c.company);
            if (!c.department.isEmpty()) b.withValue(Organization.DEPARTMENT, c.department);
            if (!c.title.isEmpty()) b.withValue(Organization.TITLE, c.title);
            ops.add(b.build());
        }

        for (ContactData.Item u : c.urls) {
            ops.add(data(raw, Website.CONTENT_ITEM_TYPE)
                    .withValue(Website.URL, u.value)
                    .withValue(Website.TYPE, Website.TYPE_OTHER)
                    .build());
        }

        if (!c.note.isEmpty()) {
            ops.add(data(raw, Note.CONTENT_ITEM_TYPE)
                    .withValue(Note.NOTE, c.note)
                    .build());
        }

        if (!c.birthday.isEmpty()) {
            ops.add(data(raw, Event.CONTENT_ITEM_TYPE)
                    .withValue(Event.START_DATE, c.birthday)
                    .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                    .build());
        }
        if (!c.anniversary.isEmpty()) {
            ops.add(data(raw, Event.CONTENT_ITEM_TYPE)
                    .withValue(Event.START_DATE, c.anniversary)
                    .withValue(Event.TYPE, Event.TYPE_ANNIVERSARY)
                    .build());
        }

        if (c.photo != null && c.photo.length > 0) {
            ops.add(data(raw, Photo.CONTENT_ITEM_TYPE)
                    .withValue(Photo.PHOTO, c.photo)
                    .build());
        }
    }

    /** Paket hajmini (Binder ~1 MB chegarasi) taxminiy baholash. */
    private static int estimateBytes(ContactData c) {
        int n = 512 + (c.photo != null ? c.photo.length : 0);
        n += 64 * (c.phones.size() + c.emails.size() + c.urls.size() + c.addresses.size());
        n += 2 * (c.note.length() + c.fullName.length());
        return n;
    }

    // ------------------------------------------------------------------ takrorlanishni aniqlash

    /**
     * Kontakt "imzosi": ism + har bir raqam/email. Barcha imzolar telefonda mavjud bo'lsa,
     * kontakt takror hisoblanadi. Ism va raqam birga tekshiriladi, shuning uchun bir xil
     * (masalan, uy) raqamini ishlatadigan turli odamlar adashtirilmaydi.
     */
    private static ArrayList<String> signature(ContactData c) {
        String name = normalizeName(c.displayName());
        ArrayList<String> sig = new ArrayList<>();
        for (ContactData.Item p : c.phones) {
            sig.add(name + "|p" + ContactData.phoneKey(p.value));
        }
        for (ContactData.Item e : c.emails) {
            sig.add(name + "|e" + e.value.toLowerCase(Locale.ROOT));
        }
        if (sig.isEmpty()) sig.add(name + "|");
        return sig;
    }

    /** Kichik harf, bitta bo'sh joy va bir xil apostrof (oʻ, o', o’ — bir xil yoziladi). */
    private static String normalizeName(String s) {
        String t = s.toLowerCase(Locale.ROOT)
                .replace('\u02BB', '\'')   // ʻ
                .replace('\u02BC', '\'')   // ʼ
                .replace('\u2019', '\'')   // ’
                .replace('\u2018', '\'')   // ‘
                .replace('`', '\'');
        return t.trim().replaceAll("\\s+", " ");
    }
}
