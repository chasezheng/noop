package android.content

/**
 * The one Android type the included analytics sources name, declared here so they compile against a
 * plain JVM.
 *
 * `Baselines.recalibrateRecoveryBaselines` takes a prefs editor because the analytics layer is
 * Context-free and the caller owns the Context; nothing on the calorie path calls it. A stub rather
 * than the SDK's `android.jar` deliberately: the jar would also satisfy an Android import the
 * include list should have rejected, and the include list is what stops this harness compiling
 * something the app does not.
 *
 * Add a member here only when a source the include list needs names one, and never a body — a stub
 * that RAN would be a second implementation of shipped behaviour, which is the thing this module
 * exists to avoid.
 */
interface SharedPreferences {
    interface Editor {
        fun putLong(key: String, value: Long): Editor
    }
}
