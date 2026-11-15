package app.memoryvault.ui.p2.showcase

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Requires the isolated target and one explicitly selected synthetic test class. */
class ShowcaseIsolationRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        check(context.packageName == TEST_PACKAGE) { "SHOWCASE_TEST_PACKAGE_MISMATCH" }
        check(targetContext.packageName == TARGET_PACKAGE) { "SHOWCASE_TARGET_PACKAGE_MISMATCH" }
        val expectedClass = when (arguments.getString("p2_showcase")) {
            "boundary" -> P2ShowcaseIsolationTest::class.java.name
            "review" -> P2SyntheticShowcaseTest::class.java.name
            "probe" -> P2SyntheticShowcaseTest::class.java.name
            "recording" -> P2RecordingUiTest::class.java.name
            else -> error("SHOWCASE_MODE_REQUIRED")
        }
        check(arguments.getString("class") == expectedClass) { "SHOWCASE_CLASS_MISMATCH" }
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == TARGET_PACKAGE) { "SHOWCASE_APPLICATION_TARGET_MISMATCH" }
        check(className == ShowcaseApplication::class.java.name) { "SHOWCASE_APPLICATION_CLASS_MISMATCH" }
        return super.newApplication(cl, className, context)
    }

    override fun onStart() {
        check(targetContext.applicationContext is ShowcaseApplication) { "SHOWCASE_APPLICATION_NOT_ACTIVE" }
        super.onStart()
    }

    private companion object {
        const val TARGET_PACKAGE = "app.memoryvault.showcase"
        const val TEST_PACKAGE = "app.memoryvault.showcase.test"
    }
}
