// Sürüm numarası tam sayı olmalı; her güncellemede artır
version = 1

cloudstream {
    description = "test"
    authors = listOf("Kalkan")

    /**
     * Durum:
     * 0: Çalışmıyor
     * 1: Çalışıyor
     * 2: Yavaş
     * 3: Sadece beta
     */
    status = 3

    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=example.org&sz=%size%"
}
