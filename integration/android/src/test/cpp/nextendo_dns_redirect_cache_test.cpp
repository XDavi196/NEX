#include <cassert>
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

    assert(!Set("*.nintendo.net", "203.0.113.10"));
    assert(!Set("bad host.example", "203.0.113.10"));
    assert(!Set("good.example", "not-an-ip"));
    assert(!Set("good.example", "256.1.2.3"));
    assert(!Set("good.example", "0.0.0.0"));
    assert(!Resolve("unknown.example").has_value());

    Clear();
    assert(Size() == 0);
    assert(!Resolve("smm2-auth.nintendo.net").has_value());
    return 0;
}
