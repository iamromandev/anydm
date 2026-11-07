package dev.anydm.desktop

private const val MB = 1024L * 1024
private const val GB = 1024L * MB

fun populatedSample() =
    HomeState(
        downloads =
            listOf(
                DownloadItem("1", "Project.zip", 200 * MB, 176 * MB, 4 * MB, DownloadStatus.Downloading),
                DownloadItem("2", "report.pdf", 5 * MB, 5 * MB, 0, DownloadStatus.Finished),
            ),
        network = NetworkInfo(2, 14 * MB),
        freeBytes = 78 * GB + GB / 2,
    )

fun emptySample() = HomeState(emptyList(), NetworkInfo(1, 0), 78 * GB + GB / 2)
