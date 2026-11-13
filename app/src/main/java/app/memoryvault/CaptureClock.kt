package app.memoryvault

fun interface CaptureClock { fun now(): Long }

/** Visibly labeled replay demo; elapsed recognition limits never use this wall clock. */
object ReplayCaptureClock : CaptureClock {
    override fun now(): Long = DemoConfig.DEMO_DATE.atTime(14, 58)
        .atZone(DemoConfig.DEMO_ZONE).toInstant().toEpochMilli()
}
