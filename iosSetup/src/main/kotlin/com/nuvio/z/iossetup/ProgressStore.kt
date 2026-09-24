package com.nuvio.z.iossetup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ProgressStore(
    val path: Path = defaultProgressPath(),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    fun exists(): Boolean = Files.isRegularFile(path)

    fun load(): SetupState? = runCatching {
        json.decodeFromString<SetupState>(Files.readString(path))
    }.getOrNull()

    fun save(state: SetupState) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(state))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    fun clear() {
        Files.deleteIfExists(path)
    }

    fun serialized(state: SetupState): String = json.encodeToString(state)

    companion object {
        fun defaultProgressPath(): Path {
            val os = System.getProperty("os.name").lowercase()
            val base = if (os.contains("mac")) {
                Path.of(System.getProperty("user.home"), "Library", "Application Support")
            } else {
                Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Nuvio Z iOS Setup")
            }
            return if (os.contains("mac")) base.resolve("Nuvio Z iOS Setup/setup-state.json")
            else base.resolve("setup-state.json")
        }
    }
}
