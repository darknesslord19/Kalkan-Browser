// Sürüm numarası tam sayı olmalı; her güncellemede artır
version = 1

cloudstream {
    description = "@@DESCRIPTION@@"
    authors = listOf("@@AUTHOR@@")

    /**
     * Durum:
     * 0: Çalışmıyor
     * 1: Çalışıyor
     * 2: Yavaş
     * 3: Sadece beta
     */
    status = 3

    tvTypes = listOf(@@TVTYPES_GRADLE@@)
    language = "@@LANG@@"
    iconUrl = "https://www.google.com/s2/favicons?domain=@@HOST@@&sz=%size%"
}
