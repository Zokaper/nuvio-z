package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosBackgroundTransferReconcilerTest {
    private val r = IosBackgroundTransferReconciler
    private val running = IosBackgroundTransferReconciler.NativeState.RUNNING

    @Test fun sessionIdentifierIsStableAndBundleSpecific() {
        assertEquals("com.nuvio.app.z.debug.downloads.background.v1", r.sessionIdentifier("com.nuvio.app.z.debug"))
        assertEquals("com.nuvio.app.z.downloads.background.v1", r.sessionIdentifier(null))
    }
    @Test fun nativeTaskReattachesAfterRelaunch() {
        assertEquals(IosBackgroundTransferReconciler.Action.ATTACH, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, running)).action)
    }
    @Test fun nativeProgressWinsByteReconciliation() {
        val result = r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, running, 10, 25))
        assertEquals(IosBackgroundTransferReconciler.Action.UPDATE_PROGRESS, result.action)
        assertEquals(25, result.reconciledBytes)
    }
    @Test fun missingNativeTaskCreatesExactlyOneReplacement() {
        assertEquals(IosBackgroundTransferReconciler.Action.CREATE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.QUEUED, IosBackgroundTransferReconciler.NativeState.MISSING)).action)
    }
    @Test fun completedNativeTaskCompletesRepository() {
        assertEquals(IosBackgroundTransferReconciler.Action.COMPLETE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, IosBackgroundTransferReconciler.NativeState.COMPLETED, destinationExists = true)).action)
    }
    @Test fun failedNativeTaskRetries() {
        assertEquals(IosBackgroundTransferReconciler.Action.RETRY, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.DOWNLOADING, IosBackgroundTransferReconciler.NativeState.FAILED)).action)
    }
    @Test fun userPauseAndCancellationAreDistinct() {
        assertEquals(IosBackgroundTransferReconciler.Action.CANCEL_NATIVE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.USER_PAUSED, running)).action)
        assertEquals(IosBackgroundTransferReconciler.Action.KEEP_PAUSED, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.USER_PAUSED, IosBackgroundTransferReconciler.NativeState.MISSING)).action)
    }
    @Test fun deletedDownloadRemovesStaleNativeTask() {
        assertEquals(IosBackgroundTransferReconciler.Action.REMOVE_STALE_NATIVE, r.reconcile(snapshot(IosBackgroundTransferReconciler.RepositoryState.MISSING, running)).action)
    }
    @Test fun legacyPartRestartsOnlyWithoutNativeOwner() {
        assertTrue(r.shouldRestartLegacyPartial(50, false)); assertFalse(r.shouldRestartLegacyPartial(50, true))
        assertFalse(r.shouldRestartLegacyPartial(0, false))
    }
    @Test fun rangeHeaderOnlyEmitsForPositiveBytes() {
        assertNull(r.computeRangeHeader(0)); assertEquals("bytes=1048576-", r.computeRangeHeader(1_048_576))
    }
    @Test fun ignoredRangeResponseRestartsFromZero() {
        val result = r.reconcileResponse(200, true, 5_000, null, 100_000, 100_000)
        assertTrue(result.isSuccess); assertFalse(result.isPartialResume); assertEquals(0, result.startingBytes)
    }
    @Test fun aligned206MayAppend() {
        val result = r.reconcileResponse(206, true, 5_000, "bytes 5000-99999/100000", 95_000, 100_000)
        assertTrue(result.isSuccess); assertTrue(result.isPartialResume); assertEquals(100_000, result.totalBytes)
    }
    @Test fun misaligned206MustRestartInsteadOfConcatenating() {
        val result = r.reconcileResponse(206, true, 5_000, "bytes 0-99999/100000", 100_000, 100_000)
        assertFalse(result.isSuccess); assertTrue(result.shouldRestartFromZero)
    }
    @Test fun exact416MeansExistingPartialIsComplete() {
        val result = r.reconcileResponse(416, true, 100_000, "bytes */100000", 0, 100_000)
        assertTrue(result.isAlreadyComplete); assertFalse(result.shouldRestartFromZero)
    }
    @Test fun overlong416PartialRestarts() {
        val result = r.reconcileResponse(416, true, 105_000, "bytes */100000", 0, 100_000)
        assertFalse(result.isSuccess); assertTrue(result.shouldRestartFromZero)
    }

    private fun snapshot(
        repositoryState: IosBackgroundTransferReconciler.RepositoryState,
        nativeState: IosBackgroundTransferReconciler.NativeState,
        repositoryBytes: Long = 0,
        nativeBytes: Long = 0,
        destinationExists: Boolean = false,
    ) = IosBackgroundTransferReconciler.Snapshot(
        repositoryState, nativeState, repositoryBytes, nativeBytes, destinationExists,
    )
}
