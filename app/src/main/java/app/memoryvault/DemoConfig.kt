package app.memoryvault

import java.time.LocalDate
import java.time.ZoneId

object DemoConfig {
    val DEMO_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    val DEMO_DATE: LocalDate = LocalDate.of(2026, 11, 8)
}
