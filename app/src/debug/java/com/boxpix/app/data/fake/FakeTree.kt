package com.boxpix.app.data.fake

import kotlin.random.Random

sealed interface FakeNode {
    var name: String
    var mtime: Long
}

class FolderNode(
    override var name: String,
    override var mtime: Long,
    val children: MutableList<FakeNode> = mutableListOf(),
) : FakeNode

class FileNode(
    override var name: String,
    override var mtime: Long,
    val sizeBytes: Long,
    /** EXIF capture date — unused until M3, seeded now so the timeline has data. */
    val takenAtEpochSeconds: Long,
    val mimeType: String,
    val durationSeconds: Long? = null,
) : FakeNode

/**
 * Deterministic fake photo library (~190 medias): same seed, same tree.
 * Shape: /Photos with _Inbox (exactly 38 items), Family, Trips 2026 (with
 * subfolders), Scans, Screenshots, Video. Capture dates spread over 2024-2026.
 */
object FakeTree {

    private const val TAKEN_MIN = 1_704_067_200L // 2024-01-01
    private const val TAKEN_MAX = 1_785_542_400L // 2026-08-01

    fun seed(random: Random): FolderNode {
        var imgCounter = 1000

        fun takenAt() = random.nextLong(TAKEN_MIN, TAKEN_MAX)

        fun jpg(): FileNode {
            val taken = takenAt()
            return FileNode(
                name = "IMG_${imgCounter++}.jpg",
                mtime = taken + random.nextLong(0, 259_200),
                sizeBytes = random.nextLong(800_000, 8_000_000),
                takenAtEpochSeconds = taken,
                mimeType = "image/jpeg",
            )
        }

        fun heic(): FileNode {
            val taken = takenAt()
            return FileNode(
                name = "IMG_E${imgCounter++}.heic",
                mtime = taken + random.nextLong(0, 259_200),
                sizeBytes = random.nextLong(600_000, 5_000_000),
                takenAtEpochSeconds = taken,
                mimeType = "image/heic",
            )
        }

        fun mp4(): FileNode {
            val taken = takenAt()
            return FileNode(
                name = "VID_${imgCounter++}.mp4",
                mtime = taken + random.nextLong(0, 259_200),
                sizeBytes = random.nextLong(30_000_000, 300_000_000),
                takenAtEpochSeconds = taken,
                mimeType = "video/mp4",
                durationSeconds = random.nextLong(5, 180),
            )
        }

        fun screenshot(index: Int): FileNode {
            val taken = takenAt()
            return FileNode(
                name = "Screenshot_2025-0${1 + index % 9}-${10 + index % 19}_1${index % 10}3012.png",
                mtime = taken,
                sizeBytes = random.nextLong(200_000, 3_000_000),
                takenAtEpochSeconds = taken,
                mimeType = "image/png",
            )
        }

        fun scan(index: Int): FileNode {
            val taken = takenAt()
            return FileNode(
                name = "Scan_2024_${"%02d".format(index + 1)}.png",
                mtime = taken,
                sizeBytes = random.nextLong(500_000, 4_000_000),
                takenAtEpochSeconds = taken,
                mimeType = "image/png",
            )
        }

        fun folder(name: String, vararg content: FakeNode) =
            FolderNode(name, mtime = random.nextLong(TAKEN_MIN, TAKEN_MAX), children = content.toMutableList())

        fun medias(count: Int, producer: () -> FileNode) = Array(count) { producer() }

        val inbox = folder(
            "_Inbox",
            *medias(35) { jpg() }, heic(), heic(), mp4(), // exactly 38 items
        )
        val family = folder("Family", *medias(52) { jpg() }, heic(), heic(), heic())
        val trips = folder(
            "Trips 2026",
            folder("Corsica", *medias(18) { jpg() }),
            folder("Lisbon", *medias(14) { jpg() }, heic()),
            *medias(15) { jpg() }, mp4(),
        )
        val scans = folder("Scans", *Array(12) { scan(it) })
        val screenshots = folder("Screenshots", *Array(20) { screenshot(it) })
        val video = folder("Video", *medias(8) { mp4() })

        return folder("", folder("Photos", inbox, family, trips, scans, screenshots, video))
    }
}
