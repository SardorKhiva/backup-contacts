# Kontakt zaxira

Telefondagi kontaktlarni `.vcf` faylga saqlaydi va fayldan tiklaydi.
Java, Android 5.0+ (API 21). Tashqi kutubxonalarsiz, orqa fonda ishlamaydi.

## Android Studio'ga ulash

1. **New Project → Empty Views Activity**
   - Name: `Kontakt zaxira`
   - Package name: `com.example.kontaktzaxira` (boshqa nom bersangiz, Java fayllarning birinchi qatoridagi `package` ni ham almashtiring)
   - Language: **Java**, Minimum SDK: **API 21**
2. Shu arxivdagi `app/src/main/` ichidan `java/`, `res/` va `AndroidManifest.xml` ni o'z loyihangizdagi `app/src/main/` ga nusxalang, mavjud fayllarni **almashtirib**
   (shablon yaratgan `MainActivity`, `activity_main.xml`, `themes.xml`, `colors.xml`, `strings.xml` almashadi; `mipmap` ikonkalari o'zgarmaydi).
3. `app/build.gradle(.kts)` da:
   - `dependencies` ichidan `appcompat`, `material`, `activity`, `constraintlayout` qatorlarini o'chiring (kerak emas; test kutubxonalari qolaversin).
   - `release` bloki: `isMinifyEnabled = true` va `isShrinkResources = true` (Groovy: `minifyEnabled true`, `shrinkResources true`).
4. **Sync Project** → **Run**.

`applicationId` dagi `com.example` Google Play uchun yaramaydi, nashr qilishdan oldin o'zgartiring.

## Fayllar

| Fayl | Vazifasi |
|---|---|
| `MainActivity` | Yagona ekran: ruxsat, fayl tanlash, progress, natija |
| `ContactsReader` | Telefon kontaktlarini oqim bilan o'qiydi (xotira kontaktlar soniga bog'liq emas) |
| `ContactsExporter` | O'qilganlarni `.vcf` ga yozadi |
| `ContactsImporter` | `.vcf` ni o'qib, 400 tagacha amal bilan paketlab yozadi; hisob tanlash; takrorlarni o'tkazib yuboradi |
| `VCardWriter` | vCard 3.0, UTF-8, 75 belgilik qator bo'linishi |
| `VCardParser` | vCard 2.1 / 3.0 / 4.0, QUOTED-PRINTABLE, UTF-8/UTF-16 |
| `ContactData`, `PhotoUtil`, `Job` | Model, rasm kichraytirish, callback'lar |

## Qanday tekshirilgan

- Resurslar `aapt2 link` bilan (API 34 android.jar), Java `javac -Xlint:all` bilan ogohlantirishsiz kompilyatsiya qilingan.
- `VCardParser` / `VCardWriter` 168 ta tekshiruvdan o'tgan: Android 2.1 (QP, kirill), iPhone 3.0, Google (qator bo'linishi), vCard 4.0, UTF-16, yozib-qayta o'qish.
- Takrorlanishni aniqlash (imzo) 14 ta tekshiruvdan o'tgan.
- **Telefon yoki emulyatorda sinalmagan:** kontakt bazasi bilan ishlash (`ContactsReader`, `applyBatch`), fayl tanlash oynasi va ko'rinish. Birinchi ishga tushirishda quyidagini tekshiring.

## Birinchi sinov ro'yxati (emulyatorda)

1. Kontaktlar ilovasida 3-5 ta kontakt qo'shing (biri kirillcha, biri `Oʻ`/`Gʻ` belgili, biri rasm va manzil bilan).
2. **Saqlash** → fayl yarating → Fayllar ilovasida `.vcf` ni ochib, matnni ko'ring (`VERSION:3.0`, ismlar buzilmagan).
3. Kontaktlarni o'chiring → **Tiklash** → fayl → hisob tanlang → kontaktlar qaytganini tekshiring.
4. Tiklashni yana bir bor bajaring: hammasi "Avvaldan bor edi" deb chiqishi kerak (takror bo'lmasligi).
5. Faylni boshqa telefon yoki Google Kontaktlarga yuklab, ismlar to'g'ri ochilishini ko'ring.

## Ma'lum cheklovlar

- Rasm kichik (thumbnail) o'lchamda saqlanadi; "Rasmlarni ham saqlash" o'chiq bo'lsa, rasmlar yozilmaydi.
- iPhone'ning maxsus yorliqlari (`X-ABLabel`) hisobga olinmaydi: raqam saqlanadi, yorlig'i "Boshqa" bo'ladi.
- Windows-1251 (ANSI) kodlashdagi eski fayllar qo'llab-quvvatlanmaydi.
- Kontaktlari yo'q yangi telefonda hisoblar ro'yxati bo'sh bo'ladi (faqat "Faqat shu telefonda" ko'rinadi); Google hisob sinxronlangach u ham chiqadi.
- Tashqi hisobga (masalan Google) yozilgan kontaktlar tizim tomonidan sinxronlanadi; "faqat shu telefonda" tanlansa, sinxronlanmaydi.
