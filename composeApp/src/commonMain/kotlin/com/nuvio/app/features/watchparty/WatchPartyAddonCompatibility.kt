package com.nuvio.app.features.watchparty

import com.nuvio.app.features.addons.ManagedAddon

/** A credential-free description of the enabled addons that can affect stream resolution. */
fun watchPartyAddonSignature(addons: List<ManagedAddon>): List<PartyAddonSignature> = addons
    .asSequence()
    .filter { it.isActive }
    .mapNotNull { it.manifest }
    .filter { manifest -> manifest.resources.any { it.name.equals("stream", ignoreCase = true) } }
    .map { PartyAddonSignature(id = it.id, version = it.version) }
    .distinctBy { it.id to it.version }
    .sortedWith(compareBy(PartyAddonSignature::id, PartyAddonSignature::version))
    .toList()

data class PartyAddonMismatch(
    val missing: List<PartyAddonSignature>,
    val extra: List<PartyAddonSignature>,
) {
    val differs: Boolean get() = missing.isNotEmpty() || extra.isNotEmpty()
}

fun comparePartyAddonSignatures(
    host: List<PartyAddonSignature>,
    participant: List<PartyAddonSignature>,
): PartyAddonMismatch {
    val hostSet = host.toSet()
    val participantSet = participant.toSet()
    return PartyAddonMismatch(
        missing = (hostSet - participantSet).sortedWith(compareBy(PartyAddonSignature::id, PartyAddonSignature::version)),
        extra = (participantSet - hostSet).sortedWith(compareBy(PartyAddonSignature::id, PartyAddonSignature::version)),
    )
}
