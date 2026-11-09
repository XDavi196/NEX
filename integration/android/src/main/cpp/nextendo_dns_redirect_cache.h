// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <cctype>
#include <cstddef>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace Common::NextendoDnsRedirectCache {

inline constexpr std::size_t MaxEntries = 32;
inline constexpr std::size_t MaxHostnameBytes = 253;
inline std::mutex cache_mutex;
struct Entry {
    std::string hostname;
    std::string ipv4;
};

inline std::vector<Entry> cache_entries;

struct ResolvedRoute {
    std::string hostname;
    std::string ipv4;
    std::uint16_t port{};
};

inline constexpr std::size_t MaxResolvedRoutes = 64;
inline std::vector<ResolvedRoute> resolved_routes;

inline std::optional<std::string> NormalizeHostname(const std::string& value) {
    if (value.empty() || value.size() > MaxHostnameBytes) {
        return std::nullopt;
    }

    std::string hostname;
    hostname.reserve(value.size());
    for (const unsigned char character : value) {
        if (character > 0x7F || !(std::isalnum(character) || character == '-' || character == '.')) {
            return std::nullopt;
        }
        hostname.push_back(static_cast<char>(std::tolower(character)));
    }

    if (!hostname.empty() && hostname.back() == '.') {
        hostname.pop_back();
    }
    if (hostname.empty() || hostname.size() > MaxHostnameBytes) {
        return std::nullopt;
    }

    std::size_t label_start = 0;
    while (label_start < hostname.size()) {
        const std::size_t separator = hostname.find('.', label_start);
        const std::size_t label_end = separator == std::string::npos ? hostname.size() : separator;
        const std::size_t label_size = label_end - label_start;
        if (label_size == 0 || label_size > 63 ||
            hostname[label_start] == '-' || hostname[label_end - 1] == '-') {
            return std::nullopt;
        }
        if (separator == std::string::npos) {
            break;
        }
        label_start = separator + 1;
    }
    return hostname;
}

inline bool IsValidIpv4(const std::string& value) {
    if (value.empty() || value.size() > 15) {
        return false;
    }

    std::size_t offset = 0;
    unsigned int parts = 0;
    while (offset < value.size()) {
        const std::size_t separator = value.find('.', offset);
        const std::size_t end = separator == std::string::npos ? value.size() : separator;
        const std::size_t length = end - offset;
        if (length == 0 || length > 3) {
            return false;
        }

        unsigned int octet = 0;
        for (std::size_t index = offset; index < end; ++index) {
            const char character = value[index];
            if (character < '0' || character > '9') {
                return false;
            }
            octet = octet * 10 + static_cast<unsigned int>(character - '0');
        }
        if (octet > 255 || ++parts > 4) {
            return false;
        }

        if (separator == std::string::npos) {
            offset = value.size();
        } else {
            offset = separator + 1;
        }
    }
    return parts == 4;
}

inline std::optional<std::array<std::uint8_t, 4>> ParseIpv4Address(const std::string& value) {
    if (!IsValidIpv4(value)) {
        return std::nullopt;
    }
    std::array<std::uint8_t, 4> result{};
    std::size_t offset = 0;
    for (std::size_t part = 0; part < result.size(); ++part) {
        const auto separator = value.find('.', offset);
        const auto end = separator == std::string::npos ? value.size() : separator;
        unsigned int octet = 0;
        for (std::size_t i = offset; i < end; ++i) {
            octet = octet * 10U + static_cast<unsigned int>(value[i] - '0');
        }
        result[part] = static_cast<std::uint8_t>(octet);
        offset = end + 1;
    }
    return result;
}

/** Adds/replaces one exact hostname mapping. Wildcards and hostnames-as-targets are rejected. */
inline bool Set(const std::string& supplied_hostname, const std::string& ipv4) {
    const auto hostname = NormalizeHostname(supplied_hostname);
    if (!hostname || !IsValidIpv4(ipv4) || ipv4 == "0.0.0.0" || ipv4 == "255.255.255.255") {
        return false;
    }

    std::lock_guard lock{cache_mutex};
    const auto found = std::find_if(cache_entries.begin(), cache_entries.end(),
        [&hostname](const Entry& entry) { return entry.hostname == *hostname; });
    if (found != cache_entries.end()) {
        if (found->ipv4 != ipv4) {
            const auto hostname = found->hostname;
            resolved_routes.erase(
                std::remove_if(resolved_routes.begin(), resolved_routes.end(),
                    [&hostname](const ResolvedRoute& entry) { return entry.hostname == hostname; }),
                resolved_routes.end());
        }
        found->ipv4 = ipv4;
        return true;
    }
    if (cache_entries.size() >= MaxEntries) {
        return false;
    }
    cache_entries.push_back(Entry{*hostname, ipv4});
    return true;
}

/** Returns the mapped IPv4 address for one exact hostname, case-insensitively. */
inline std::optional<std::string> Resolve(const std::string& supplied_hostname) {
    const auto hostname = NormalizeHostname(supplied_hostname);
    if (!hostname) {
        return std::nullopt;
    }
    std::lock_guard lock{cache_mutex};
    const auto found = std::find_if(cache_entries.begin(), cache_entries.end(),
        [&hostname](const Entry& entry) { return entry.hostname == *hostname; });
    return found == cache_entries.end() ? std::nullopt : std::optional<std::string>{found->ipv4};
}
/**
 * Record the address/port returned for an exact configured redirect. This
 * bounded reverse table supports TLS SNI recovery and lost-IP BSD connects.
 */
inline void RecordResolved(const std::string& supplied_hostname, const std::string& ipv4,
                           std::uint16_t port) {
    const auto hostname = NormalizeHostname(supplied_hostname);
    if (!hostname || !IsValidIpv4(ipv4)) {
        return;
    }
    std::lock_guard lock{cache_mutex};
    const auto configured = std::find_if(cache_entries.begin(), cache_entries.end(),
        [&hostname](const Entry& entry) { return entry.hostname == *hostname; });
    if (configured == cache_entries.end() || configured->ipv4 != ipv4) {
        return;
    }
    resolved_routes.erase(
        std::remove_if(resolved_routes.begin(), resolved_routes.end(),
            [&hostname, &ipv4, port](const ResolvedRoute& entry) {
                return entry.hostname == *hostname && entry.ipv4 == ipv4 && entry.port == port;
            }),
        resolved_routes.end());
    if (resolved_routes.size() >= MaxResolvedRoutes) {
        resolved_routes.erase(resolved_routes.begin(),
                              resolved_routes.begin() + static_cast<std::ptrdiff_t>(
                                  resolved_routes.size() - MaxResolvedRoutes + 1));
    }
    resolved_routes.push_back(ResolvedRoute{*hostname, ipv4, port});
}

/** Prefer the exact peer IP/port; fall back to DEN/Citron's last-resolved host for that IP. */
inline std::optional<std::string> HostnameForPeer(const std::string& ipv4, std::uint16_t port) {
    std::lock_guard lock{cache_mutex};
    if (port != 0) {
        for (auto it = resolved_routes.rbegin(); it != resolved_routes.rend(); ++it) {
            if (it->ipv4 == ipv4 && it->port == port) {
                return it->hostname;
            }
        }
    }
    // GetHostByName and some SSL clients do not retain a service port. Preserve
    // the same last-resolved-host fallback used by the Nextendo reference clients.
    for (auto it = resolved_routes.rbegin(); it != resolved_routes.rend(); ++it) {
        if (it->ipv4 == ipv4) {
            return it->hostname;
        }
    }
    return std::nullopt;
}

/** Recover a DNS-redirected IPv4 when the guest keeps only the service port. */
inline std::optional<std::array<std::uint8_t, 4>> ResolveIpv4ForPort(std::uint16_t port) {
    if (port == 0) {
        return std::nullopt;
    }
    std::lock_guard lock{cache_mutex};
    for (auto it = resolved_routes.rbegin(); it != resolved_routes.rend(); ++it) {
        if (it->port == port) {
            return ParseIpv4Address(it->ipv4);
        }
    }
    return std::nullopt;
}

inline void Clear() {
    std::lock_guard lock{cache_mutex};
    cache_entries.clear();
    resolved_routes.clear();
}

inline std::size_t Size() {
    std::lock_guard lock{cache_mutex};
    return cache_entries.size();
}

} // namespace Common::NextendoDnsRedirectCache
