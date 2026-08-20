// Stand-in for the generated Compose resource bundle. StreamModels.kt reaches it once, for the
// fallback stream label, and pulling the real generator in would mean running Gradle.

package nuvio.composeapp.generated.resources

class StringResource(val value: String)

object Res {
    object string {
        val stream_default_name = StringResource("Stream")
    }
}
