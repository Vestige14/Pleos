package app.memoryvault.data

import java.time.OffsetDateTime

internal fun t(hm: String): Long =
    OffsetDateTime.parse("2026-11-08T$hm:00+09:00").toInstant().toEpochMilli()

internal val day = listOf(
    Visit("v1", "2026-11-08", "대부도 칼국수집", 37.2830, 126.5810, t("12:00"), t("13:10")),
    Visit("v2", "2026-11-08", "방아머리해변", 37.2846, 126.5779, t("13:30"), t("14:50")),
    Visit("v3", "2026-11-08", "바다 앞 카페", 37.2790, 126.5850, t("15:10"), t("16:00")),
)

internal val seed = SeedDay("2026-11-08", t("14:58"), day)
