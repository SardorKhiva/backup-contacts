package com.example.kontaktzaxira;

/** Saqlash/tiklash jarayoni uchun umumiy kichik interfeyslar. */
final class Job {

    private Job() {
    }

    interface Progress {
        /** Fon oqimdan chaqiriladi. total <= 0 bo'lsa, jami son noma'lum. */
        void onProgress(int done, int total);
    }

    interface Cancel {
        boolean isCancelled();
    }
}
