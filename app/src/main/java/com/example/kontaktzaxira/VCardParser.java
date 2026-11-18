package com.example.kontaktzaxira;

import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * .vcf faylni kontaktlarga ajratadi. vCard 2.1 (Android eski eksporti, QUOTED-PRINTABLE),
 * 3.0 (Google, iPhone) va 4.0 versiyalarini tushunadi. Fayl oqim bilan o'qiladi,
 * shuning uchun katta fayllar ham xotirani to'ldirmaydi.
 */
final class VCardParser {

    interface Listener {
        /** @return false qaytarilsa, o'qish to'xtatiladi. */
        boolean onContact(ContactData contact) throws IOException;
    }

    private static final HashSet<String> PHONE_IGNORED = set(
            "VOICE", "PREF", "MSG", "VIDEO", "BBS", "MODEM", "TEXT", "TEXTPHONE",
            "PARCEL", "POSTAL", "DOM", "INTL", "INTERNET");
    private static final HashSet<String> EMAIL_IGNORED = set("INTERNET", "PREF", "X400", "VOICE");
    private static final HashSet<String> ADDRESS_IGNORED = set(
            "POSTAL", "PARCEL", "DOM", "INTL", "PREF", "VOICE");

    private static final Pattern DATE_FULL = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATE_COMPACT = Pattern.compile("\\d{8}");
    private static final Pattern DATE_NO_YEAR = Pattern.compile("--\\d{2}-?\\d{2}");

    private VCardParser() {
    }

    // ------------------------------------------------------------ ochiq metodlar

    /** Fayldagi "BEGIN:VCARD" qatorlari sonini sanaydi (progress uchun). */
    static int countCards(InputStream in) throws IOException {
        BufferedReader r = openReader(in);
        int n = 0;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.regionMatches(true, 0, "BEGIN:VCARD", 0, 11)) n++;
        }
        return n;
    }

    /** Har bir yaroqli kontakt uchun listener'ni chaqiradi. */
    static void parse(InputStream in, Listener listener) throws IOException {
        LineSource src = new LineSource(openReader(in));
        ContactData cur = null;
        String line;
        while ((line = src.next()) != null) {
            if (line.isEmpty()) continue;
            Prop p = parseProp(line);
            if (p == null) continue;

            if (p.name.equals("BEGIN")) {
                if (p.value.trim().equalsIgnoreCase("VCARD")) cur = new ContactData();
                continue;
            }
            if (cur == null) continue;

            if (p.name.equals("END")) {
                if (p.value.trim().equalsIgnoreCase("VCARD")) {
                    ContactData done = cur;
                    cur = null;
                    done.finish();
                    if (done.isMeaningful() && !listener.onContact(done)) return;
                }
                continue;
            }
            apply(cur, p);
        }
    }

    /**
     * Sanani Android tushunadigan ko'rinishga keltiradi: yyyy-MM-dd yoki --MM-dd.
     * Tanib bo'lmaydigan format bo'lsa, bo'sh qator qaytaradi.
     */
    static String normalizeDate(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        int t = v.indexOf('T');
        if (t > 0) v = v.substring(0, t);
        if (DATE_COMPACT.matcher(v).matches()) {
            v = v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6);
        }
        if (DATE_FULL.matcher(v).matches()) {
            // Apple yili yo'q tug'ilgan kunni 1604 yil bilan belgilaydi
            if (v.startsWith("1604-")) return "--" + v.substring(5);
            return v;
        }
        if (DATE_NO_YEAR.matcher(v).matches()) {
            String d = v.substring(2).replace("-", "");
            return "--" + d.substring(0, 2) + "-" + d.substring(2);
        }
        return "";
    }

    // ------------------------------------------------------------ xossalarni qo'llash

    private static void apply(ContactData c, Prop p) {
        switch (p.name) {
            case "N": {
                List<String> n = splitStructured(text(p));
                c.family = part(n, 0);
                c.given = part(n, 1);
                c.middle = part(n, 2);
                c.prefix = part(n, 3);
                c.suffix = part(n, 4);
                break;
            }
            case "FN":
                c.fullName = unescape(text(p)).trim();
                break;
            case "NICKNAME":
                if (c.nickname.isEmpty()) c.nickname = unescape(text(p)).trim();
                break;
            case "ORG": {
                if (c.company.isEmpty() && c.department.isEmpty()) {
                    List<String> o = splitStructured(text(p));
                    c.company = part(o, 0);
                    c.department = part(o, 1);
                }
                break;
            }
            case "TITLE":
                if (c.title.isEmpty()) c.title = unescape(text(p)).trim();
                break;
            case "TEL": {
                String v = stripScheme(unescape(text(p)).trim(), "tel:");
                if (!v.isEmpty()) c.phones.add(phoneItem(p, v));
                break;
            }
            case "EMAIL": {
                String v = stripScheme(unescape(text(p)).trim(), "mailto:");
                if (!v.isEmpty()) c.emails.add(emailItem(p, v));
                break;
            }
            case "ADR": {
                ContactData.Address a = addressItem(p);
                if (!a.isEmpty()) c.addresses.add(a);
                break;
            }
            case "URL": {
                String v = unescape(text(p)).trim();
                if (!v.isEmpty()) c.urls.add(new ContactData.Item(0, "", v));
                break;
            }
            case "NOTE": {
                String v = unescape(text(p)).trim();
                if (!v.isEmpty()) c.note = c.note.isEmpty() ? v : c.note + "\n" + v;
                break;
            }
            case "BDAY":
                if (c.birthday.isEmpty()) c.birthday = normalizeDate(text(p));
                break;
            case "ANNIVERSARY":
            case "X-ANNIVERSARY":
                if (c.anniversary.isEmpty()) c.anniversary = normalizeDate(text(p));
                break;
            case "PHOTO":
                if (c.photo == null) c.photo = decodePhoto(p);
                break;
            default:
                break; // boshqa xossalar (IM, X-... va h.k.) e'tiborga olinmaydi
        }
    }

    private static ContactData.Item phoneItem(Prop p, String number) {
        boolean fax = p.has("FAX");
        boolean home = p.has("HOME");
        boolean work = p.has("WORK");
        boolean cell = p.has("CELL") || p.has("MOBILE");
        boolean pager = p.has("PAGER");
        int type;
        String label = "";
        if (fax) {
            type = home ? Phone.TYPE_FAX_HOME : work ? Phone.TYPE_FAX_WORK : Phone.TYPE_OTHER_FAX;
        } else if (cell) {
            type = work ? Phone.TYPE_WORK_MOBILE : Phone.TYPE_MOBILE;
        } else if (pager) {
            type = work ? Phone.TYPE_WORK_PAGER : Phone.TYPE_PAGER;
        } else if (work) {
            type = Phone.TYPE_WORK;
        } else if (home) {
            type = Phone.TYPE_HOME;
        } else if (p.has("CAR")) {
            type = Phone.TYPE_CAR;
        } else if (p.has("ISDN")) {
            type = Phone.TYPE_ISDN;
        } else if (p.has("MAIN")) {
            type = Phone.TYPE_MAIN;
        } else {
            String custom = p.customType(PHONE_IGNORED);
            if (custom != null) {
                type = Phone.TYPE_CUSTOM;
                label = custom;
            } else {
                type = Phone.TYPE_OTHER;
            }
        }
        return new ContactData.Item(type, label, number);
    }

    private static ContactData.Item emailItem(Prop p, String address) {
        int type;
        String label = "";
        if (p.has("WORK")) {
            type = Email.TYPE_WORK;
        } else if (p.has("HOME")) {
            type = Email.TYPE_HOME;
        } else if (p.has("CELL") || p.has("MOBILE")) {
            type = Email.TYPE_MOBILE;
        } else {
            String custom = p.customType(EMAIL_IGNORED);
            if (custom != null) {
                type = Email.TYPE_CUSTOM;
                label = custom;
            } else {
                type = Email.TYPE_OTHER;
            }
        }
        return new ContactData.Item(type, label, address);
    }

    private static ContactData.Address addressItem(Prop p) {
        List<String> a = splitStructured(text(p));
        ContactData.Address r = new ContactData.Address();
        r.pobox = part(a, 0);
        r.extended = part(a, 1);
        r.street = part(a, 2);
        r.city = part(a, 3);
        r.region = part(a, 4);
        r.postcode = part(a, 5);
        r.country = part(a, 6);
        if (p.has("WORK")) {
            r.type = StructuredPostal.TYPE_WORK;
        } else if (p.has("HOME")) {
            r.type = StructuredPostal.TYPE_HOME;
        } else {
            String custom = p.customType(ADDRESS_IGNORED);
            if (custom != null) {
                r.type = StructuredPostal.TYPE_CUSTOM;
                r.label = custom;
            } else {
                r.type = StructuredPostal.TYPE_OTHER;
            }
        }
        return r;
    }

    private static byte[] decodePhoto(Prop p) {
        String v = p.value.trim();
        if (v.isEmpty()) return null;
        boolean dataUri = v.regionMatches(true, 0, "data:", 0, 5);
        if (!dataUri && !p.isBase64()) return null; // tashqi havola (URL) qo'llab-quvvatlanmaydi
        if (dataUri) {
            int i = v.indexOf("base64,");
            if (i < 0) return null;
            v = v.substring(i + 7);
        }
        try {
            byte[] bytes = Base64.decode(v, Base64.DEFAULT);
            if (bytes.length == 0) return null;
            return PhotoUtil.limit(bytes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ qator va parametrlar

    /** "GURUH.NOM;PARAM=qiymat;BARE:QIYMAT" ko'rinishidagi bitta (yig'ilgan) qator. */
    private static final class Prop {
        String name = "";
        String value = "";
        String encoding = "";
        String charset = "";
        final ArrayList<String> types = new ArrayList<>();

        boolean has(String type) {
            for (String t : types) {
                if (t.equalsIgnoreCase(type)) return true;
            }
            return false;
        }

        boolean isQuotedPrintable() {
            return encoding.equals("QUOTED-PRINTABLE");
        }

        boolean isBase64() {
            return encoding.equals("BASE64") || encoding.equals("B");
        }

        /** Standart bo'lmagan birinchi TYPE qiymati (foydalanuvchining maxsus yorlig'i) yoki null. */
        String customType(HashSet<String> ignored) {
            for (String t : types) {
                String s = t.trim();
                if (s.regionMatches(true, 0, "X-", 0, 2)) s = s.substring(2);
                if (s.isEmpty()) continue;
                if (ignored.contains(s.toUpperCase(Locale.ROOT))) continue;
                return s;
            }
            return null;
        }
    }

    private static Prop parseProp(String line) {
        int colon = indexOfColon(line);
        if (colon <= 0) return null;

        Prop p = new Prop();
        p.value = line.substring(colon + 1);

        List<String> parts = splitOutsideQuotes(line.substring(0, colon), ';');
        String nameToken = parts.get(0).trim();
        int dot = nameToken.lastIndexOf('.');
        if (dot >= 0) nameToken = nameToken.substring(dot + 1); // "item1.TEL" -> "TEL"
        p.name = nameToken.toUpperCase(Locale.ROOT);

        for (int i = 1; i < parts.size(); i++) {
            String part = parts.get(i).trim();
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) {
                // vCard 2.1: "TEL;CELL;PREF:..." yoki "...;QUOTED-PRINTABLE:..."
                String up = part.toUpperCase(Locale.ROOT);
                if (up.equals("QUOTED-PRINTABLE") || up.equals("BASE64")) {
                    p.encoding = up;
                } else if (!up.equals("7BIT") && !up.equals("8BIT")) {
                    p.types.add(part);
                }
            } else {
                String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String val = unquote(part.substring(eq + 1).trim());
                if (key.equals("TYPE")) {
                    for (String t : val.split(",")) {
                        t = t.trim();
                        if (!t.isEmpty()) p.types.add(t);
                    }
                } else if (key.equals("ENCODING")) {
                    p.encoding = val.toUpperCase(Locale.ROOT);
                } else if (key.equals("CHARSET")) {
                    p.charset = val;
                }
            }
        }
        return p;
    }

    private static int indexOfColon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ':' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitOutsideQuotes(String s, char sep) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                cur.append(ch);
            } else if (ch == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String stripScheme(String v, String scheme) {
        if (v.regionMatches(true, 0, scheme, 0, scheme.length())) {
            return v.substring(scheme.length()).trim();
        }
        return v;
    }

    // ------------------------------------------------------------ matnni dekodlash

    /** Qiymatni (kerak bo'lsa QUOTED-PRINTABLE'dan) dekodlaydi; escape'lar tegilmaydi. */
    private static String text(Prop p) {
        return p.isQuotedPrintable() ? decodeQuotedPrintable(p.value, p.charset) : p.value;
    }

    private static String decodeQuotedPrintable(String s, String charsetName) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch == '=' && i + 2 < n) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bos.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            int cp = s.codePointAt(i);
            if (cp < 0x80) {
                bos.write(cp);
            } else {
                byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                bos.write(b, 0, b.length);
                i += Character.charCount(cp) - 1;
            }
        }
        return bos.toString(charsetOrUtf8(charsetName));
    }

    private static Charset charsetOrUtf8(String name) {
        if (name != null && !name.isEmpty()) {
            try {
                return Charset.forName(name);
            } catch (Exception ignored) {
                // noma'lum kodlash — UTF-8 ishlatiladi
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** \n \N \\ \, \; \: ketma-ketliklarini oddiy belgilarga aylantiradi. */
    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(++i);
                sb.append(nx == 'n' || nx == 'N' ? '\n' : nx);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Escape qilinmagan ';' bo'yicha bo'ladi va har bir bo'lakni unescape qiladi. */
    static List<String> splitStructured(String raw) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' && i + 1 < raw.length()) {
                char nx = raw.charAt(++i);
                cur.append(nx == 'n' || nx == 'N' ? '\n' : nx);
            } else if (ch == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String part(List<String> list, int index) {
        return index < list.size() ? list.get(index).trim() : "";
    }

    private static HashSet<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    // ------------------------------------------------------------ fayl o'qish

    /** BOM'ni aniqlaydi (UTF-8 / UTF-16); aks holda UTF-8 deb o'qiydi. */
    private static BufferedReader openReader(InputStream in) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(new BufferedInputStream(in, 64 * 1024), 3);
        byte[] b = new byte[3];
        int n = 0;
        while (n < 3) {
            int r = pb.read(b, n, 3 - n);
            if (r < 0) break;
            n += r;
        }
        Charset cs = StandardCharsets.UTF_8;
        int skip = 0;
        if (n >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            cs = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            cs = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            skip = 3;
        }
        if (n > skip) pb.unread(b, skip, n - skip);
        return new BufferedReader(new InputStreamReader(pb, cs), 64 * 1024);
    }

    /**
     * Jismoniy qatorlarni mantiqiy qatorga yig'adi:
     * 1) bo'sh joy/tab bilan boshlangan davom qatorlari (folding),
     * 2) QUOTED-PRINTABLE qiymatdagi "=" bilan tugaydigan yumshoq uzilishlar.
     */
    private static final class LineSource {
        private final BufferedReader reader;
        private String pushed;

        LineSource(BufferedReader reader) {
            this.reader = reader;
        }

        private String raw() throws IOException {
            if (pushed != null) {
                String s = pushed;
                pushed = null;
                return s;
            }
            return reader.readLine();
        }

        String next() throws IOException {
            String first = raw();
            if (first == null) return null;
            boolean qp = looksQuotedPrintable(first);
            StringBuilder sb = new StringBuilder(first);
            while (true) {
                String nxt = raw();
                if (nxt == null) break;
                if (qp && sb.length() > 0 && sb.charAt(sb.length() - 1) == '=') {
                    sb.setLength(sb.length() - 1);
                    sb.append(nxt);
                    continue;
                }
                if (!nxt.isEmpty() && (nxt.charAt(0) == ' ' || nxt.charAt(0) == '\t')) {
                    sb.append(nxt, 1, nxt.length());
                    continue;
                }
                pushed = nxt;
                break;
            }
            return sb.toString();
        }

        private static boolean looksQuotedPrintable(String line) {
            int colon = line.indexOf(':');
            String head = colon >= 0 ? line.substring(0, colon) : line;
            return head.toUpperCase(Locale.ROOT).contains("QUOTED-PRINTABLE");
        }
    }
}
