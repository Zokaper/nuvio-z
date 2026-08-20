package org.jetbrains.compose.resources

import nuvio.composeapp.generated.resources.StringResource

suspend fun getString(resource: StringResource): String = resource.value
