package com.example.kontaktzaxira;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * Bitta kontaktning oddiy ko'rinishi.
 * ContactsReader (telefondan o'qish), VCardParser (fayldan o'qish) va
 * VCardWriter (faylga yozish) shu sinf bilan ishlaydi.
 * Barcha matnlar null bo'lmaydi va trim qilingan holda saqlanadi.
 */
final class ContactData {

    /** Telefon, email yoki sayt: type — Android CommonDataKinds.*.TYPE_* qiymati. */
    static final class Item {
        int type;
        String label;
        String value;

        Item(int type, String label, String value) {
            this.type = type;
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
        }
    }

    static final class Address {
        int type;
        String label = "";
        String pobox = "";
        String extended = "";
        String street = "";
        String city = "";
        String region = "";
        String postcode = "";
        String country = "";

        boolean isEmpty() {
            return pobox.isEmpty() && extended.isEmpty() && street.isEmpty() && city.isEmpty()
                    && region.isEmpty() && postcode.isEmpty() && country.isEmpty();
        }
    }

    String fullName = "";
    String family = "";
    String given = "";
    String middle = "";
    String prefix = "";
    String suffix = "";
    String nickname = "";
    String company = "";
    String department = "";
    String title = "";
    String note = "";
    String birthday = "";
    String anniversary = "";
    byte[] photo;

    final ArrayList<Item> phones = new ArrayList<>();
    final ArrayList<Item> emails = new ArrayList<>();
    final ArrayList<Item> urls = new ArrayList<>();
    final ArrayList<Address> addresses = new ArrayList<>();

    boolean hasStructuredName() {
        return !(family.isEmpty() && given.isEmpty() && middle.isEmpty()
                && prefix.isEmpty() && suffix.isEmpty());
    }

    boolean hasName() {
        return !fullName.isEmpty() || hasStructuredName();
    }

    /** Ko'rsatiladigan nom: ism, bo'lmasa kompaniya, telefon yoki email. */
    String displayName() {
        if (!fullName.isEmpty()) return fullName;
        String composed = join(prefix, given, middle, family, suffix);
        if (!composed.isEmpty()) return composed;
        if (!company.isEmpty()) return company;
        if (!phones.isEmpty()) return phones.get(0).value;
        if (!emails.isEmpty()) return emails.get(0).value;
        return "";
    }

    /** Ismi, raqami, emaili yoki kompaniyasi bor kontaktgina saqlashga arziydi. */
    boolean isMeaningful() {
        return hasName() || !phones.isEmpty() || !emails.isEmpty() || !company.isEmpty();
    }

    /** O'qish tugagach chaqiriladi: takroriy raqam/email/saytlarni olib tashlaydi. */
    void finish() {
        dedupe(phones, true);
        dedupe(emails, false);
        dedupe(urls, false);
    }

    private static void dedupe(ArrayList<Item> list, boolean phone) {
        if (list.size() < 2) return;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); ) {
            Item it = list.get(i);
            String key = phone ? phoneKey(it.value) : it.value.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                i++;
            } else {
                list.remove(i);
            }
        }
    }

    /**
     * Raqamlarni solishtirish kaliti: faqat raqamlar, oxirgi 9 tasi.
     * Shunda +998901234567, 998901234567 va 90 123 45 67 bir xil hisoblanadi.
     */
    static String phoneKey(String s) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') d.append(ch);
        }
        if (d.length() == 0) return s.trim().toLowerCase(Locale.ROOT);
        return d.length() > 9 ? d.substring(d.length() - 9) : d.toString();
    }

    static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }
}
