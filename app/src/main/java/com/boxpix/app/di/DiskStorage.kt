package com.boxpix.app.di

import javax.inject.Qualifier

/**
 * The undecorated disk provider (Freebox or fake), BEFORE the vault mount.
 * Only the vault layer itself may inject this; everything else uses the
 * unqualified StorageProvider, which routes `<disk root>/.vault/` paths to
 * the unlocked vault.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DiskStorage
