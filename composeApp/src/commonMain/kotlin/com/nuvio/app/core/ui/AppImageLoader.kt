package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/** Register before any image or tray cache access, including a windowless desktop launch.
 * Coil permits repeat registration after our loader exists, but rejects it after its default
 * loader was created. Keep this outside composition so background startup can register first.
 */
internal fun configureAppImageLoader() {
    if (!platformProvidesImageLoader) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .crossfade(true)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .components {
                    add(CustomPosterFallbackInterceptor())
                    add(SvgDecoder.Factory())
                    add(
                        coil3.network.ktor3.KtorNetworkFetcherFactory(
                            cacheStrategy = { coil3.network.cachecontrol.CacheControlCacheStrategy() },
                        ),
                    )
                }
                .configurePlatformImageLoader()
                .build()
        }
    }
}
