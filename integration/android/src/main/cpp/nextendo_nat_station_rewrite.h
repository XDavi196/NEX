// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace Common::NextendoNatStationRewrite {

using IPv4 = std::array<std::uint8_t, 4>;

inline std::mutex observed_ip_mutex;
inline std::optional<IPv4> observed_external_ip;

inline bool IsPrivateOrReserved(const IPv4& ip) {
    const auto a = ip[0];
    const auto b = ip[1];
    if (a == 0 || a == 10 || a == 127 || a >= 224) return true;
    if (a == 100 && b >= 64 && b <= 127) return true; // CGNAT
    if (a == 169 && b == 254) return true; // link-local
    if (a == 172 && b >= 16 && b <= 31) return true;
    if (a == 192 && b == 168) return true;
    if (a == 192 && b == 0 && (ip[2] == 0 || ip[2] == 2)) return true;
    if (a == 192 && b == 88 && ip[2] == 99) return true; // deprecated 6to4 relay anycast
    if (a == 198 && (b == 18 || b == 19)) return true; // benchmarking
    if (a == 198 && b == 51 && ip[2] == 100) return true; // documentation
    if (a == 203 && b == 0 && ip[2] == 113) return true; // documentation
    if (ip == IPv4{255, 255, 255, 255}) return true;
    return false;
}

inline std::optional<IPv4> ParsePublicIPv4(std::string_view value) {
    if (value.empty() || value.size() > 15) return std::nullopt;
    IPv4 result{};
    std::size_t offset = 0;
    for (std::size_t part = 0; part < 4; ++part) {
        const auto end = value.find('.', offset);
        const auto limit = end == std::string_view::npos ? value.size() : end;
        const auto length = limit - offset;
        if (length == 0 || length > 3 ||
            (length > 1 && value[offset] == '0')) return std::nullopt;
        unsigned int octet = 0;
        for (std::size_t i = offset; i < limit; ++i) {
            const char ch = value[i];
            if (ch < '0' || ch > '9') return std::nullopt;
            octet = octet * 10U + static_cast<unsigned int>(ch - '0');
        }
        if (octet > 255U) return std::nullopt;
        result[part] = static_cast<std::uint8_t>(octet);
        if (part < 3) {
            if (end == std::string_view::npos) return std::nullopt;
            offset = end + 1;
        } else if (end != std::string_view::npos) {
            return std::nullopt;
        }
    }
    if (IsPrivateOrReserved(result)) return std::nullopt;
    return result;
}

inline bool SetObservedExternalIPv4(std::string_view value) {
    const auto parsed = ParsePublicIPv4(value);
    if (!parsed) return false;
    std::lock_guard lock{observed_ip_mutex};
    observed_external_ip = *parsed;
    return true;
}

inline void ClearObservedExternalIPv4() {
    std::lock_guard lock{observed_ip_mutex};
    observed_external_ip.reset();
}

inline std::optional<IPv4> GetObservedExternalIPv4() {
    std::lock_guard lock{observed_ip_mutex};
    return observed_external_ip;
}

namespace Detail {

inline std::uint16_t Read16LE(std::span<const std::uint8_t> data, std::size_t offset) {
    return static_cast<std::uint16_t>(data[offset]) |
           static_cast<std::uint16_t>(static_cast<std::uint16_t>(data[offset + 1]) << 8);
}

inline std::uint32_t Read32LE(std::span<const std::uint8_t> data, std::size_t offset) {
    return static_cast<std::uint32_t>(data[offset]) |
           (static_cast<std::uint32_t>(data[offset + 1]) << 8) |
           (static_cast<std::uint32_t>(data[offset + 2]) << 16) |
           (static_cast<std::uint32_t>(data[offset + 3]) << 24);
}

inline void Append16LE(std::vector<std::uint8_t>& out, std::uint16_t value) {
    out.push_back(static_cast<std::uint8_t>(value));
    out.push_back(static_cast<std::uint8_t>(value >> 8));
}

inline void Append32LE(std::vector<std::uint8_t>& out, std::uint32_t value) {
    out.push_back(static_cast<std::uint8_t>(value));
    out.push_back(static_cast<std::uint8_t>(value >> 8));
    out.push_back(static_cast<std::uint8_t>(value >> 16));
    out.push_back(static_cast<std::uint8_t>(value >> 24));
}

inline void Write16LE(std::vector<std::uint8_t>& out, std::size_t offset,
                      std::uint16_t value) {
    out[offset] = static_cast<std::uint8_t>(value);
    out[offset + 1] = static_cast<std::uint8_t>(value >> 8);
}

inline bool ParseIPv4(std::string_view value, IPv4& result) {
    if (value.empty() || value.size() > 15) return false;
    std::size_t offset = 0;
    for (std::size_t part = 0; part < 4; ++part) {
        const auto end = value.find('.', offset);
        const auto limit = end == std::string_view::npos ? value.size() : end;
        const auto length = limit - offset;
        if (length == 0 || length > 3) return false;
        unsigned int octet = 0;
        for (std::size_t i = offset; i < limit; ++i) {
            const char ch = value[i];
            if (ch < '0' || ch > '9') return false;
            octet = octet * 10U + static_cast<unsigned int>(ch - '0');
        }
        if (octet > 255U) return false;
        result[part] = static_cast<std::uint8_t>(octet);
        if (part < 3) {
            if (end == std::string_view::npos) return false;
            offset = end + 1;
        } else if (end != std::string_view::npos) {
            return false;
        }
    }
    return true;
}

inline bool NeedsPublicAddress(std::string_view station) {
    constexpr std::string_view address_key = "address=";
    const auto address_pos = station.find(address_key);
    if (address_pos == std::string_view::npos) return false;
    const auto address_start = address_pos + address_key.size();
    const auto address_end = station.find(';', address_start);
    if (address_end == std::string_view::npos) return false;

    IPv4 address{};
    if (!ParseIPv4(station.substr(address_start, address_end - address_start), address) ||
        !IsPrivateOrReserved(address)) {
        return false;
    }

    const auto natf_pos = station.find("natf=");
    if (natf_pos == std::string_view::npos || natf_pos + 5 >= station.size()) return false;
    const char natf = station[natf_pos + 5];
    return natf >= '1' && natf <= '9';
}

inline std::string_view FormatIPv4(const IPv4& ip, char (&buffer)[16]) {
    // Bounded decimal formatter: every IPv4 address fits in 15 visible bytes.
    std::size_t pos = 0;
    for (std::size_t i = 0; i < 4; ++i) {
        unsigned int value = ip[i];
        char digits[3]{};
        std::size_t count = 0;
        do {
            digits[count++] = static_cast<char>('0' + (value % 10U));
            value /= 10U;
        } while (value != 0U);
        while (count > 0) buffer[pos++] = digits[--count];
        if (i != 3) buffer[pos++] = '.';
    }
    return std::string_view(buffer, pos);
}

struct Match {
    std::size_t length_offset;
    std::size_t old_total;
    std::vector<std::uint8_t> replacement;
};

inline bool RewriteRmcBody(std::span<const std::uint8_t> body, const IPv4& external_ip,
                           std::vector<std::uint8_t>& out, std::size_t& count) {
    constexpr std::string_view marker = "prudp:/address=";
    std::vector<Match> matches;
    std::size_t cursor = 0;
    char address_buffer[16]{};
    const auto replacement_ip = FormatIPv4(external_ip, address_buffer);

    while (cursor < body.size()) {
        const std::string_view view(reinterpret_cast<const char*>(body.data()), body.size());
        const auto marker_pos = view.find(marker, cursor);
        if (marker_pos == std::string_view::npos) break;
        if (marker_pos < 2) {
            cursor = marker_pos + marker.size();
            continue;
        }

        const std::size_t length_offset = marker_pos - 2;
        const std::uint16_t station_length = Read16LE(body, length_offset);
        if (static_cast<std::size_t>(station_length) > body.size() - marker_pos) {
            cursor = marker_pos + marker.size();
            continue;
        }

        const auto station = view.substr(marker_pos, station_length);
        if (NeedsPublicAddress(station)) {
            const auto address_pos = station.find("address=");
            const auto address_start = address_pos + 8;
            const auto address_end = station.find(';', address_start);
            std::vector<std::uint8_t> replacement;
            replacement.reserve(station.size() + replacement_ip.size());
            replacement.insert(replacement.end(), station.begin(),
                               station.begin() + static_cast<std::ptrdiff_t>(address_start));
            replacement.insert(replacement.end(), replacement_ip.begin(), replacement_ip.end());
            replacement.insert(replacement.end(),
                               station.begin() + static_cast<std::ptrdiff_t>(address_end),
                               station.end());
            if (replacement.size() > std::numeric_limits<std::uint16_t>::max()) return false;
            matches.push_back(Match{
                .length_offset = length_offset,
                .old_total = 2 + static_cast<std::size_t>(station_length),
                .replacement = std::move(replacement)
            });
            ++count;
        }
        cursor = marker_pos + station_length;
    }

    if (matches.empty()) return false;
    out.clear();
    out.reserve(body.size() + matches.size() * 15);
    cursor = 0;
    for (const auto& match : matches) {
        if (match.length_offset < cursor || match.length_offset > body.size() ||
            match.old_total > body.size() - match.length_offset) return false;
        out.insert(out.end(), body.begin() + static_cast<std::ptrdiff_t>(cursor),
                   body.begin() + static_cast<std::ptrdiff_t>(match.length_offset));
        Append16LE(out, static_cast<std::uint16_t>(match.replacement.size()));
        out.insert(out.end(), match.replacement.begin(), match.replacement.end());
        cursor = match.length_offset + match.old_total;
    }
    out.insert(out.end(), body.begin() + static_cast<std::ptrdiff_t>(cursor), body.end());
    return true;
}

} // namespace Detail

/**
 * Rewrites only outgoing, complete WebSocket binary frames containing the
 * SMM2/Nextendo NEX station URLs used by SecureConnection and MatchmakeExtension.
 *
 * The caller supplies an external IPv4 learned from two verified NNCS vantage
 * replies. No endpoint, port, public address, BAAS token, or peer address is guessed.
 * On an unrecognized or malformed frame this returns false and leaves output alone.
 */
inline bool TryFixupStationAddress(std::span<const std::uint8_t> input,
                                   const IPv4& external_ip,
                                   std::vector<std::uint8_t>& output,
                                   std::size_t* rewritten_count = nullptr) {
    if (rewritten_count != nullptr) *rewritten_count = 0;
    if (input.size() < 2 || input.size() > 2 * 1024 * 1024) return false;

    const std::uint8_t first = input[0];
    if ((first & 0x80U) == 0 || (first & 0x70U) != 0 || (first & 0x0FU) != 0x02U) {
        return false; // one complete FIN binary frame, no extensions/fragments
    }

    const bool masked = (input[1] & 0x80U) != 0;
    std::uint64_t payload_length = input[1] & 0x7FU;
    std::size_t position = 2;
    if (payload_length == 126) {
        if (input.size() < position + 2) return false;
        payload_length = (static_cast<std::uint64_t>(input[position]) << 8) |
                         input[position + 1];
        position += 2;
    } else if (payload_length == 127) {
        if (input.size() < position + 8 || (input[position] & 0x80U) != 0) return false;
        payload_length = 0;
        for (std::size_t i = 0; i < 8; ++i) {
            payload_length = (payload_length << 8) | input[position + i];
        }
        position += 8;
    }
    if (payload_length > 2 * 1024 * 1024 || payload_length > input.size()) return false;

    std::array<std::uint8_t, 4> mask{};
    if (masked) {
        if (input.size() < position + mask.size()) return false;
        std::copy_n(input.begin() + static_cast<std::ptrdiff_t>(position), mask.size(), mask.begin());
        position += mask.size();
    }
    if (input.size() != position + static_cast<std::size_t>(payload_length)) return false;

    std::vector<std::uint8_t> payload(
        input.begin() + static_cast<std::ptrdiff_t>(position), input.end());
    if (masked) {
        for (std::size_t i = 0; i < payload.size(); ++i) payload[i] ^= mask[i % 4];
    }

    if (payload.size() < 12 || payload[0] != 0x80U) return false;
    const std::size_t option_size = payload[1];
    const std::size_t rmc_offset = 12 + option_size;
    const std::uint16_t rmc_size = Detail::Read16LE(payload, 2);
    if (rmc_offset > payload.size() || rmc_size != payload.size() - rmc_offset ||
        rmc_size < 13) return false;

    const auto rmc = std::span<const std::uint8_t>(payload).subspan(rmc_offset, rmc_size);
    if (Detail::Read32LE(rmc, 0) != rmc.size() - 4) return false;
    const std::uint8_t protocol_byte = rmc[4];
    if ((protocol_byte & 0x80U) == 0) return false; // only guest requests have stations
    const std::uint8_t protocol = protocol_byte & 0x7FU;
    const std::uint32_t method = Detail::Read32LE(rmc, 9);
    const bool secure_connection = protocol == 0x0BU && (method == 0x01U || method == 0x07U);
    const bool matchmake = protocol == 0x6DU &&
        (method == 0x26U || method == 0x27U || method == 0x28U);
    if (!secure_connection && !matchmake) return false;

    constexpr std::size_t rmc_body_offset = 13;
    const auto body = rmc.subspan(rmc_body_offset);
    std::vector<std::uint8_t> rewritten_body;
    std::size_t count = 0;
    if (!Detail::RewriteRmcBody(body, external_ip, rewritten_body, count)) return false;

    const std::uint64_t new_rmc_size =
        static_cast<std::uint64_t>(rmc_body_offset) + rewritten_body.size();
    if (new_rmc_size > std::numeric_limits<std::uint16_t>::max()) return false;

    std::vector<std::uint8_t> new_rmc;
    new_rmc.reserve(static_cast<std::size_t>(new_rmc_size));
    Detail::Append32LE(new_rmc, static_cast<std::uint32_t>(new_rmc_size - 4));
    new_rmc.insert(new_rmc.end(), rmc.begin() + 4,
                   rmc.begin() + static_cast<std::ptrdiff_t>(rmc_body_offset));
    new_rmc.insert(new_rmc.end(), rewritten_body.begin(), rewritten_body.end());

    std::vector<std::uint8_t> new_payload;
    new_payload.reserve(rmc_offset + new_rmc.size());
    new_payload.insert(new_payload.end(), payload.begin(),
                       payload.begin() + static_cast<std::ptrdiff_t>(rmc_offset));
    Detail::Write16LE(new_payload, 2, static_cast<std::uint16_t>(new_rmc.size()));
    new_payload.insert(new_payload.end(), new_rmc.begin(), new_rmc.end());

    if (masked) {
        for (std::size_t i = 0; i < new_payload.size(); ++i) new_payload[i] ^= mask[i % 4];
    }

    output.clear();
    output.reserve(new_payload.size() + 14);
    output.push_back(first);
    const std::uint64_t new_length = new_payload.size();
    if (new_length <= 125) {
        output.push_back(static_cast<std::uint8_t>((masked ? 0x80U : 0U) | new_length));
    } else if (new_length <= std::numeric_limits<std::uint16_t>::max()) {
        output.push_back(static_cast<std::uint8_t>((masked ? 0x80U : 0U) | 126U));
        output.push_back(static_cast<std::uint8_t>(new_length >> 8));
        output.push_back(static_cast<std::uint8_t>(new_length));
    } else {
        output.push_back(static_cast<std::uint8_t>((masked ? 0x80U : 0U) | 127U));
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.push_back(static_cast<std::uint8_t>(new_length >> shift));
        }
    }
    if (masked) output.insert(output.end(), mask.begin(), mask.end());
    output.insert(output.end(), new_payload.begin(), new_payload.end());
    if (rewritten_count != nullptr) *rewritten_count = count;
    return true;
}

inline bool TryFixupStationAddress(std::span<const std::uint8_t> input,
                                   std::vector<std::uint8_t>& output,
                                   std::size_t* rewritten_count = nullptr) {
    const auto external_ip = GetObservedExternalIPv4();
    return external_ip && TryFixupStationAddress(input, *external_ip, output, rewritten_count);
}

} // namespace Common::NextendoNatStationRewrite
