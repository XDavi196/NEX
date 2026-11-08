package com.nex.nx.nextendo

import kotlinx.serialization.json.JsonElement

/** Builds the exact public claim profile used by Citron's Nextendo ACC path. */
internal class NextendoCitronBaasProvider(
    override val signer: NextendoBaasSigner
) : NextendoBaasProvisioningProvider {
    override fun claimsFor(
        session: NextendoSessionStore.Session
    ): Map<String, JsonElement> = NextendoCitronBaasProfile.claimsFor(session)
}
