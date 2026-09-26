package com.nuvio.app.features.downloads

/**
 * Which iOS background-transfer variant this build is (Phase 9 experiments, 2026-09-26).
 *
 * `baseline` is the `.56` model: a window of 12 tasks created *and resumed* in the foreground, the
 * system deciding how many move bytes. Experiments 10a (controlled resume) and 10b (per-host
 * connection limit) are separate branches that change this and nothing else in the baseline; the
 * probe log's `session_config` line names the variant so a log can never be read as another's.
 */
internal object IosTransferExperiment {
    const val ID: String = "baseline"
}
