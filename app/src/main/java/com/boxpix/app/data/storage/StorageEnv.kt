package com.boxpix.app.data.storage

import kotlinx.coroutines.flow.Flow

/**
 * How the app is wired to storage in the current build variant.
 * Debug exposes a runtime switch (fake ↔ Freebox, no rebuild needed for the
 * M1 gate); release is always the real provider with no fake controls.
 */
class StorageEnv(
    val useFakeProvider: Flow<Boolean>,
    val fakeControls: FakeControls?,
)

/** Debug-only levers of the fake provider, surfaced in the Settings debug group. */
interface FakeControls {
    /** Next request stalls like a drive waking from sleep. */
    fun sleepDisk()

    /** Regenerates the seeded fake tree from scratch. */
    fun resetData()
}
