package app.skerry.shared

import android.os.Build

actual val platformName: String = "Android ${Build.VERSION.SDK_INT}"
