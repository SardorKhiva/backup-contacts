package com.example.kontaktzaxira;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
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
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;

import java.io.IOException;
import java.util.Arrays;

/**
 * Telefondagi (Kontaktlar ilovasida ko'rinadigan) barcha kontaktlarni o'qiydi.
 * Bitta Data so'rovi contact_id bo'yicha tartiblanadi va kontaktlar birin-ketin
 * oqim bilan beriladi, shuning uchun xotira kontaktlar soniga bog'liq emas.
 * Birlashtirilgan (aggregated) kontaktlar ishlatiladi: bir odam Google va telefon
 * hisobida takrorlansa ham, faylga bir marta yoziladi.
 */
final class ContactsReader {

    interface Sink {
        /** @return false qaytarilsa, o'qish to'xtatiladi. */
        boolean accept(ContactData contact) throws IOException;
    }

    // Ustunlar tartibi (projection bilan bir xil)
    private static final int C_ID = 0;
    private static final int C_MIME = 1;
    private static final int C_PRIMARY = 2;
    private static final int D1 = 3;
    private static final int D2 = 4;
    private static final int D3 = 5;
    private static final int D4 = 6;
    private static final int D5 = 7;
    private static final int D6 = 8;
    private static final int D7 = 9;
    private static final int D8 = 10;
    private static final int D9 = 11;
    private static final int D10 = 12;
    private static final int C_PHOTO = 13;

    private static final String[] PROJECTION = {
            Data.CONTACT_ID, Data.MIMETYPE, Data.IS_PRIMARY,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5,
            Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10
    };

    private static final String[] MIMES = {
            StructuredName.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE,
            Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
            StructuredPostal.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
            Website.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE
    };

    private ContactsReader() {
    }

    /** Ko'rinadigan kontaktlar soni (progress uchun taxminiy jami). */
    static int countContacts(ContentResolver cr) {
        Cursor c = null;
        try {
            c = cr.query(Contacts.CONTENT_URI, new String[]{Contacts._ID},
                    Contacts.IN_VISIBLE_GROUP + "=1", null, null);
            return c == null ? 0 : c.getCount();
        } catch (RuntimeException e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    static void readAll(ContentResolver cr, boolean withPhotos, Sink sink) throws IOException {
        String[] projection = PROJECTION;
        String[] mimes = MIMES;
        if (withPhotos) {
            projection = Arrays.copyOf(PROJECTION, PROJECTION.length + 1);
            projection[PROJECTION.length] = Data.DATA15;
            mimes = Arrays.copyOf(MIMES, MIMES.length + 1);
            mimes[MIMES.length] = Photo.CONTENT_ITEM_TYPE;
        }

        StringBuilder where = new StringBuilder(Data.CONTACT_ID + " IS NOT NULL AND "
                + Data.MIMETYPE + " IN (");
        for (int i = 0; i < mimes.length; i++) {
            where.append(i == 0 ? "?" : ",?");
        }
        where.append(')');

        // Faqat Kontaktlar ilovasida ko'rinadigan kontaktlar (yashirin guruhlar chiqarib tashlanadi)
        Uri uri = Data.CONTENT_URI.buildUpon()
                .appendQueryParameter("visible_contacts_only", "true")
                .build();

        Cursor cur = cr.query(uri, projection, where.toString(), mimes, Data.CONTACT_ID + " ASC");
        if (cur == null) return;
        try {
            ContactData c = null;
            long currentId = -1;
            while (cur.moveToNext()) {
                long id = cur.getLong(C_ID);
                if (c == null || id != currentId) {
                    if (c != null && !emit(c, sink)) return;
                    c = new ContactData();
                    currentId = id;
                }
                readRow(cur, c, withPhotos);
            }
            if (c != null) emit(c, sink);
        } finally {
            cur.close();
        }
    }

    private static boolean emit(ContactData c, Sink sink) throws IOException {
        c.finish();
        if (!c.isMeaningful()) return true;
        return sink.accept(c);
    }

    private static void readRow(Cursor cur, ContactData c, boolean withPhotos) {
        String mime = cur.getString(C_MIME);
        if (mime == null) return;
        boolean primary = cur.getInt(C_PRIMARY) != 0;

        switch (mime) {
            case StructuredName.CONTENT_ITEM_TYPE:
                if (!c.hasName() || primary) {
                    c.fullName = s(cur, D1);
                    c.given = s(cur, D2);
                    c.family = s(cur, D3);
                    c.prefix = s(cur, D4);
                    c.middle = s(cur, D5);
                    c.suffix = s(cur, D6);
                }
                break;

            case Nickname.CONTENT_ITEM_TYPE:
                if (c.nickname.isEmpty()) c.nickname = s(cur, D1);
                break;

            case Phone.CONTENT_ITEM_TYPE: {
                String number = s(cur, D1);
                if (number.isEmpty()) break;
                int type = cur.getInt(D2);
                String label = s(cur, D3);
                if (type == Phone.TYPE_CUSTOM && label.isEmpty()) type = Phone.TYPE_OTHER;
                c.phones.add(new ContactData.Item(type, label, number));
                break;
            }

            case Email.CONTENT_ITEM_TYPE: {
                String address = s(cur, D1);
                if (address.isEmpty()) break;
                int type = cur.getInt(D2);
                String label = s(cur, D3);
                if (type == Email.TYPE_CUSTOM && label.isEmpty()) type = Email.TYPE_OTHER;
                c.emails.add(new ContactData.Item(type, label, address));
                break;
            }

            case StructuredPostal.CONTENT_ITEM_TYPE: {
                ContactData.Address a = new ContactData.Address();
                a.type = cur.getInt(D2);
                a.label = s(cur, D3);
                a.street = s(cur, D4);
                a.pobox = s(cur, D5);
                a.extended = s(cur, D6);
                a.city = s(cur, D7);
                a.region = s(cur, D8);
                a.postcode = s(cur, D9);
                a.country = s(cur, D10);
                if (a.isEmpty()) {
                    // Ba'zi ilovalar faqat bitta matnli manzil saqlaydi
                    a.street = s(cur, D1).replaceAll("[\\r\\n]+", ", ");
                }
                if (a.type == StructuredPostal.TYPE_CUSTOM && a.label.isEmpty()) {
                    a.type = StructuredPostal.TYPE_OTHER;
                }
                if (!a.isEmpty()) c.addresses.add(a);
                break;
            }

            case Organization.CONTENT_ITEM_TYPE:
                if (c.company.isEmpty() && c.title.isEmpty() && c.department.isEmpty()) {
                    c.company = s(cur, D1);
                    c.title = s(cur, D4);
                    c.department = s(cur, D5);
                }
                break;

            case Website.CONTENT_ITEM_TYPE: {
                String url = s(cur, D1);
                if (!url.isEmpty()) c.urls.add(new ContactData.Item(0, "", url));
                break;
            }

            case Note.CONTENT_ITEM_TYPE: {
                String note = s(cur, D1);
                if (!note.isEmpty()) c.note = c.note.isEmpty() ? note : c.note + "\n" + note;
                break;
            }

            case Event.CONTENT_ITEM_TYPE: {
                int type = cur.getInt(D2);
                String date = VCardParser.normalizeDate(s(cur, D1));
                if (date.isEmpty()) break;
                if (type == Event.TYPE_BIRTHDAY && c.birthday.isEmpty()) {
                    c.birthday = date;
                } else if (type == Event.TYPE_ANNIVERSARY && c.anniversary.isEmpty()) {
                    c.anniversary = date;
                }
                break;
            }

            case Photo.CONTENT_ITEM_TYPE:
                if (withPhotos && c.photo == null) {
                    byte[] blob = cur.getBlob(C_PHOTO);
                    if (blob != null && blob.length > 0) c.photo = blob;
                }
                break;

            default:
                break;
        }
    }

    private static String s(Cursor cur, int index) {
        String v = cur.getString(index);
        return v == null ? "" : v.trim();
    }
}
