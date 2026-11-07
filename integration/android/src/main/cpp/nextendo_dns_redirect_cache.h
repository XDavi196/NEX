// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
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
inline std::vector<struct Entry> cache_entries;

struct Entry {
    std::string hostname;
    std::string ipv4;
};

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

inline void Clear() {
    std::lock_guard lock{cache_mutex};
    cache_entries.clear();
}

inline std::size_t Size() {
    std::lock_guard lock{cache_mutex};
    return cache_entries.size();
}

} // namespace Common::NextendoDnsRedirectCache
