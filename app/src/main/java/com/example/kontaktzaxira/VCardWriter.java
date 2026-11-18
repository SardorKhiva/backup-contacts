package com.example.kontaktzaxira;

import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.util.Base64;

import java.io.IOException;
import java.io.Writer;

/**
 * Kontaktni vCard 3.0 formatida (UTF-8, CRLF qator oxiri) yozadi.
 * 3.0 versiyasi Google Kontaktlar, iPhone va Android bilan eng yaxshi mos keladi.
 */
final class VCardWriter {

    private static final String CRLF = "\r\n";
    private static final int MAX_LINE = 75;

    private VCardWriter() {
    }

    static void write(Writer out, ContactData c, boolean withPhoto) throws IOException {
        String name = c.displayName();

        line(out, "BEGIN:VCARD");
        line(out, "VERSION:3.0");
        if (c.hasStructuredName()) {
            line(out, "N:" + esc(c.family) + ";" + esc(c.given) + ";" + esc(c.middle)
                    + ";" + esc(c.prefix) + ";" + esc(c.suffix));
        } else {
            line(out, "N:;" + esc(name) + ";;;");
        }
        line(out, "FN:" + esc(name));

        if (!c.nickname.isEmpty()) {
            line(out, "NICKNAME:" + esc(c.nickname));
        }
        if (!c.company.isEmpty() || !c.department.isEmpty()) {
            line(out, "ORG:" + esc(c.company) + ";" + esc(c.department));
        }
        if (!c.title.isEmpty()) {
            line(out, "TITLE:" + esc(c.title));
        }
        for (ContactData.Item p : c.phones) {
            line(out, "TEL" + typeParam(phoneTypes(p)) + ":" + single(p.value));
        }
        for (ContactData.Item e : c.emails) {
            line(out, "EMAIL" + typeParam(emailTypes(e)) + ":" + single(e.value));
        }
        for (ContactData.Address a : c.addresses) {
            line(out, "ADR" + typeParam(addressTypes(a)) + ":" + esc(a.pobox) + ";" + esc(a.extended)
                    + ";" + esc(a.street) + ";" + esc(a.city) + ";" + esc(a.region)
                    + ";" + esc(a.postcode) + ";" + esc(a.country));
        }
        for (ContactData.Item u : c.urls) {
            line(out, "URL:" + single(u.value));
        }
        if (!c.birthday.isEmpty()) {
            line(out, "BDAY:" + single(c.birthday));
        }
        if (!c.anniversary.isEmpty()) {
            line(out, "X-ANNIVERSARY:" + single(c.anniversary));
        }
        if (!c.note.isEmpty()) {
            line(out, "NOTE:" + esc(c.note));
        }
        if (withPhoto && c.photo != null && c.photo.length > 0) {
            line(out, "PHOTO;ENCODING=b;TYPE=" + photoType(c.photo) + ":"
                    + Base64.encodeToString(c.photo, Base64.NO_WRAP));
        }
        line(out, "END:VCARD");
    }

    // ---------------------------------------------------------------- turlar

    private static String phoneTypes(ContactData.Item p) {
        switch (p.type) {
            case Phone.TYPE_HOME:
                return "HOME";
            case Phone.TYPE_MOBILE:
                return "CELL";
            case Phone.TYPE_WORK:
                return "WORK";
            case Phone.TYPE_FAX_WORK:
                return "WORK,FAX";
            case Phone.TYPE_FAX_HOME:
                return "HOME,FAX";
            case Phone.TYPE_OTHER_FAX:
                return "FAX";
            case Phone.TYPE_PAGER:
                return "PAGER";
            case Phone.TYPE_WORK_MOBILE:
                return "WORK,CELL";
            case Phone.TYPE_WORK_PAGER:
                return "WORK,PAGER";
            case Phone.TYPE_CAR:
                return "CAR";
            case Phone.TYPE_ISDN:
                return "ISDN";
            case Phone.TYPE_MAIN:
                return "MAIN";
            case Phone.TYPE_CUSTOM: {
                String l = label(p.label);
                return l.isEmpty() ? "VOICE" : l;
            }
            default:
                return "VOICE";
        }
    }

    private static String emailTypes(ContactData.Item e) {
        switch (e.type) {
            case Email.TYPE_HOME:
                return "INTERNET,HOME";
            case Email.TYPE_WORK:
                return "INTERNET,WORK";
            case Email.TYPE_MOBILE:
                return "INTERNET,CELL";
            case Email.TYPE_CUSTOM: {
                String l = label(e.label);
                return l.isEmpty() ? "INTERNET" : "INTERNET," + l;
            }
            default:
                return "INTERNET";
        }
    }

    private static String addressTypes(ContactData.Address a) {
        switch (a.type) {
            case StructuredPostal.TYPE_HOME:
                return "HOME";
            case StructuredPostal.TYPE_WORK:
                return "WORK";
            case StructuredPostal.TYPE_CUSTOM:
                return label(a.label);
            default:
                return "";
        }
    }

    private static String typeParam(String types) {
        return types.isEmpty() ? "" : ";TYPE=" + types;
    }

    private static String photoType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "PNG";
        }
        if (b.length > 2 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "GIF";
        }
        return "JPEG";
    }

    // ------------------------------------------------------------ matn yordamchilari

    /** Parametr qiymati uchun yaroqsiz belgilarni olib tashlaydi. */
    private static String label(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == ';' || ch == ':' || ch == ',' || ch == '"') continue;
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** vCard 3.0 matn qiymati: \ ; , va yangi qatorni escape qiladi. */
    static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case ';':
                    sb.append("\\;");
                    break;
                case ',':
                    sb.append("\\,");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\n') break; // CRLF -> bitta \n
                    sb.append("\\n");
                    break;
                default:
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Raqam, email, sayt kabi qiymatlar: faqat qator uzilishlarini bo'sh joyga almashtiradi. */
    private static String single(String s) {
        if (s == null) return "";
        return s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /** 75 belgidan uzun qatorlarni RFC bo'yicha bo'ladi (davomi bo'sh joy bilan boshlanadi). */
    private static void line(Writer out, String s) throws IOException {
        int len = s.length();
        if (len <= MAX_LINE) {
            out.write(s);
            out.write(CRLF);
            return;
        }
        int pos = 0;
        int limit = MAX_LINE;
        while (pos < len) {
            int end = Math.min(len, pos + limit);
            if (end < len && Character.isHighSurrogate(s.charAt(end - 1))) end--;
            if (pos > 0) out.write(' ');
            out.write(s, pos, end - pos);
            out.write(CRLF);
            pos = end;
            limit = MAX_LINE - 1;
        }
    }
}
