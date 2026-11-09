#include <array>
#include <cassert>
#include <cstdint>
#include <string>

#include "../../main/cpp/nextendo_dns_redirect_cache.h"

int main() {
    using Common::NextendoDnsRedirectCache::Clear;
    using Common::NextendoDnsRedirectCache::Resolve;
    using Common::NextendoDnsRedirectCache::Set;
    using Common::NextendoDnsRedirectCache::Size;

    Clear();
    assert(Set("SMM2-AUTH.NINTENDO.NET", "203.0.113.10"));
    assert(Resolve("smm2-auth.nintendo.net") == "203.0.113.10");
    assert(Resolve("SMM2-AUTH.NINTENDO.NET.") == "203.0.113.10");

    assert(Set("smm2-auth.nintendo.net", "198.51.100.20"));
    assert(Size() == 1);
    assert(Resolve("smm2-auth.nintendo.net") == "198.51.100.20");

    using Common::NextendoDnsRedirectCache::HostnameForPeer;
    using Common::NextendoDnsRedirectCache::RecordResolved;
    using Common::NextendoDnsRedirectCache::ResolveIpv4ForPort;
    const std::array<std::uint8_t, 4> expected_redirect_ip{198, 51, 100, 20};

    RecordResolved("smm2-auth.nintendo.net", "198.51.100.20", 443);
    assert(HostnameForPeer("198.51.100.20", 443) == "smm2-auth.nintendo.net");
    assert(ResolveIpv4ForPort(443) == expected_redirect_ip);

    assert(Set("npln-auth.nintendo.net", "198.51.100.20"));
    RecordResolved("npln-auth.nintendo.net", "198.51.100.20", 7575);
    assert(HostnameForPeer("198.51.100.20", 7575) == "npln-auth.nintendo.net");
    assert(HostnameForPeer("198.51.100.20", 8443) == "npln-auth.nintendo.net");
    assert(ResolveIpv4ForPort(7575) == expected_redirect_ip);

    assert(!Set("*.nintendo.net", "203.0.113.10"));
    assert(!Set("bad host.example", "203.0.113.10"));
    assert(!Set("good.example", "not-an-ip"));
    assert(!Set("good.example", "256.1.2.3"));
    assert(!Set("good.example", "0.0.0.0"));
    assert(!Resolve("unknown.example").has_value());

    using Common::NextendoDnsRedirectCache::SetNncsRedirectIps;
    assert(SetNncsRedirectIps("203.0.113.11", "198.51.100.21"));
    assert(Resolve("nncs1-smm2.n.n.srv.nintendo.net") == "203.0.113.11");
    assert(Resolve("NNCS2-SMM2.N.N.SRV.NINTENDO.NET.") == "198.51.100.21");
    assert(!Resolve("xnncs2-smm2.n.n.srv.nintendo.net").has_value());
    assert(!Resolve("nncs2-smm2.example.net").has_value());
    assert(!Resolve("nncs2-a.b.n.n.srv.nintendo.net").has_value());
    assert(!SetNncsRedirectIps("203.0.113.11", "203.0.113.11"));
    assert(!SetNncsRedirectIps("0.0.0.0", "198.51.100.21"));

    RecordResolved("nncs2-smm2.n.n.srv.nintendo.net", "198.51.100.21", 10025);
    assert(HostnameForPeer("198.51.100.21", 10025) == "nncs2-smm2.n.n.srv.nintendo.net");
    const std::array<std::uint8_t, 4> expected_nat_ip{198, 51, 100, 21};
    assert(ResolveIpv4ForPort(10025) == expected_nat_ip);

    Clear();
    assert(Size() == 0);
    assert(!Resolve("smm2-auth.nintendo.net").has_value());
    assert(!Resolve("nncs1-smm2.n.n.srv.nintendo.net").has_value());
    assert(!Resolve("nncs2-smm2.n.n.srv.nintendo.net").has_value());
    return 0;
}
