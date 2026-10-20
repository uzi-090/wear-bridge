package io.wearbridge.watch.ble

/** App pairing is independent of a system Bluetooth bond and of the current radio connection. */
enum class PairingPhase { UNPAIRED, PAIRING, PAIRED, REMOVING }

data class DevicePairing(val phase: PairingPhase = PairingPhase.UNPAIRED, val address: String? = null) {
    val enabled get() = phase == PairingPhase.PAIRED || phase == PairingPhase.PAIRING
    fun canAccess(link: LinkSnapshot) = phase == PairingPhase.PAIRED && link.state == LinkState.READY

    /**
     * Whether a connecting phone may proceed to Bluetooth bonding and app authentication.
     *
     * During explicit setup any phone may try: the matching-code approval decides, and an iPhone
     * that bonds again arrives with a new Bluetooth address (it uses private addresses), so a saved
     * address can't be required. Once paired, only the saved phone is let in, unless its system bond
     * is gone; then it can only come back with a new address and must still prove the trusted app key.
     */
    fun accepts(candidate: String, savedBonded: Boolean = true) = when (phase) {
        PairingPhase.PAIRING -> true
        PairingPhase.PAIRED -> address == candidate || !savedBonded
        else -> false
    }

    fun begin() = copy(phase = PairingPhase.PAIRING)

    /** The phone proved an approved app key; its current address becomes the saved one. */
    fun approved(candidate: String) = if (enabled) DevicePairing(PairingPhase.PAIRED, candidate) else this
    fun removing() = copy(phase = PairingPhase.REMOVING)
    fun removed(forget: Boolean) = DevicePairing(address = if (forget) null else address)

    companion object {
        /** Restore approval only when both the persisted state and trusted app identity agree. */
        fun restore(savedPhase: String?, address: String?, hasTrustedIdentity: Boolean, pendingRemovalForget: Boolean? = null): DevicePairing {
            val phase = PairingPhase.entries.firstOrNull { it.name == savedPhase }
                ?: if (savedPhase == null && hasTrustedIdentity) PairingPhase.PAIRED else PairingPhase.UNPAIRED
            return DevicePairing(
                if (phase == PairingPhase.PAIRED && hasTrustedIdentity && pendingRemovalForget == null) phase else PairingPhase.UNPAIRED,
                if (pendingRemovalForget == true) null else address,
            )
        }
    }
}
