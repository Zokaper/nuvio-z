import Foundation
import ComposeApp

// Nuvio Z: the lock screen, Control Center and headset commands, routed through Kotlin.
//
// `NowPlayingController.swift` calls these instead of moving mpv, and each one hands the request to
// Kotlin's `PlayerRemoteCommands` - so in Watch Together the party decides who may move the player,
// exactly as for an on-screen button. mpv is moved directly only when no player is composed to ask.
// A Z file, so the upstream player files carry one call per handler rather than the routing.
extension MPVPlayerViewController {
    func remotePlay() {
        if let remoteCommands { remoteCommands.play() } else { playPlayback() }
    }

    func remotePause() {
        if let remoteCommands { remoteCommands.pause() } else { pausePlayback() }
    }

    func remoteTogglePlayPause() {
        if let remoteCommands {
            remoteCommands.togglePlayPause()
        } else if isPlayerPlaying {
            pausePlayback()
        } else {
            playPlayback()
        }
    }

    func remoteSeekTo(_ ms: Int64) {
        if let remoteCommands { remoteCommands.seekTo(positionMs: ms) } else { seekToMs(ms) }
    }

    func remoteSeekBy(_ ms: Int64) {
        if let remoteCommands { remoteCommands.seekBy(offsetMs: ms) } else { seekByMs(ms, exact: true) }
    }
}
