package app.skerry.shared

import platform.UIKit.UIDevice

actual val platformName: String =
    "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"
