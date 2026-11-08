package com.nex.nx.nextendo

/**
 * Exact NEX auth hosts for titles with a public Nextendo server profile.
 *
 * This registry controls DNS routing only. It does not imply that every
 * title has verified matchmaking, the same NEX version, the same PRUDP access
 * key, or a completed NAT/P2P path.
 */
internal object NextendoGameNetworkProfiles {
    data class Profile(
        val title: String,
        val gameServerId: String,
        val status: String
    ) {
        val authHostname: String
            get() = "g${gameServerId.lowercase()}-lp1.s.n.srv.nintendo.net"
    }

    val profiles: List<Profile> = listOf(
        Profile("Super Mario Maker 2", "22306D00", "experimental"),
        Profile("Mario Kart 8 Deluxe", "2B309E01", "supported"),
        Profile("Splatoon 2", "2DF33D01", "supported"),
        Profile("Super Smash Bros. Ultimate", "23380901", "supported"),
        Profile("Luigi's Mansion 3", "20DE2100", "profile-listed"),
        Profile("ARMS", "25C08801", "supported"),
        Profile("Mario Tennis Aces", "23932A00", "supported"),
        Profile("Super Mario Bros. 35", "21F12900", "supported"),
        Profile("PAC-MAN 99", "2E471600", "supported")
    )

    /** Nintendo/Nextendo device-auth hosts documented for exact DNS routing. */
    val deviceAuthHosts: List<String> = listOf(
        "dauth-lp1.ndas.srv.nintendo.net",
        "dcert-lp1.ndas.srv.nintendo.net",
        "aauth.hac.lp1.ndas.srv.nintendo.net",
        "aauth-lp1.ndas.srv.nintendo.net",
        "acert-lp1.ndas.srv.nintendo.net",
        "dragons.hac.lp1.dragons.nintendo.net",
        "dragonst.hac.lp1.dragons.nintendo.net"
    )

    /** Hosts whose original SNI must be preserved by Eden's resolver. */
    val allExactAuthHosts: List<String> =
        (profiles.map { it.authHostname } + deviceAuthHosts)
            .distinct()
}
