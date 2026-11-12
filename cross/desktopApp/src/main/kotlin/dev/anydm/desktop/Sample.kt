package dev.anydm.desktop

private const val MB = 1024L * 1024
private const val GB = 1024L * MB
private val WIFI_SPEED = (7.1 * MB).toLong()

val sampleUsage = mapOf("wifi" to (200.1 * MB).toLong(), "usb" to GB * 3 / 2)

fun populatedSample() =
    HomeState(
        downloads =
            listOf(
                DownloadItem("1", "Project.zip", 200 * MB, 176 * MB, 4 * MB, DownloadStatus.Downloading),
                DownloadItem("2", "report.pdf", 5 * MB, 5 * MB, 0, DownloadStatus.Finished),
            ),
        networks =
            listOf(
                Network("wifi", "Wi-Fi", NetworkKind.WiFi, true, WIFI_SPEED),
                Network("usb", "USB Tethering", NetworkKind.UsbTethering, true, 14 * MB - WIFI_SPEED),
            ),
        freeBytes = 78 * GB + GB / 2,
    )

fun emptySample() =
    HomeState(
        emptyList(),
        listOf(Network("wifi", "Wi-Fi", NetworkKind.WiFi, true, 0)),
        78 * GB + GB / 2,
    )
