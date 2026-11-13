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
        val gameServerId: String
    ) {
        val authHostname: String
            get() = "g${gameServerId.lowercase()}-lp1.s.n.srv.nintendo.net"
    }

    val profiles: List<Profile> = listOf(
        Profile("Super Mario Maker 2", "22306D00"),
        Profile("Mario Kart 8 Deluxe", "2B309E01"),
        Profile("Splatoon 2", "2DF33D01"),
        Profile("Super Smash Bros. Ultimate", "23380901"),
        Profile("Luigi's Mansion 3", "20DE2100"),
        Profile("ARMS", "25C08801"),
        Profile("Mario Tennis Aces", "23932A00"),
        Profile("Super Mario Bros. 35", "21F12900"),
        Profile("PAC-MAN 99", "2E471600"),
        Profile("Tetris 99", "23BDA200"),
        Profile("Animal Crossing: New Horizons", "2EE2E300")
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

    /**
     * Hosts whose original SNI must be preserved by Eden's resolver.
     *
     * BAAS is deliberately not routed here: NEX has no authorized BAAS
     * provider yet, and must not inherit a hostname from another emulator.
     */
    val allExactAuthHosts: List<String> =
        (profiles.map { it.authHostname } + deviceAuthHosts).distinct()
}
