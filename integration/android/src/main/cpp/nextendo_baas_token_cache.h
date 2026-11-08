// SPDX-FileCopyrightText: Copyright 2026 NEX contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <utility>
#include <vector>

namespace Common::NextendoBaasTokenCache {

inline constexpr std::size_t MaxTokenBytes = 8192;
inline std::mutex cache_mutex;
inline std::vector<std::uint8_t> token_bytes;

inline bool IsBase64Url(char value) {
    return (value >= 'A' && value <= 'Z') ||
           (value >= 'a' && value <= 'z') ||
           (value >= '0' && value <= '9') ||
           value == '-' || value == '_';
}

/** Checks the compact-JWS shape; signature verification remains the BAAS server's job. */
inline void Clear();

inline bool IsCompactJws(const std::uint8_t* data, std::size_t size) {
    if (data == nullptr || size == 0 || size > MaxTokenBytes) {
        return false;
    }
    std::size_t separators = 0;
    std::size_t segment_length = 0;
    for (std::size_t index = 0; index < size; ++index) {
        const auto character = static_cast<char>(data[index]);
        if (character == '.') {
            if (segment_length == 0 || separators >= 2) {
                return false;
            }
            ++separators;
            segment_length = 0;
        } else {
            if (!IsBase64Url(character)) {
                return false;
            }
            ++segment_length;
        }
    }
    return separators == 2 && segment_length != 0;
}

inline bool Set(const std::uint8_t* data, std::size_t size) {
    if (!IsCompactJws(data, size)) {
        Clear();
        return false;
    }
    std::vector<std::uint8_t> replacement(data, data + size);
    std::lock_guard lock{cache_mutex};
    std::fill(token_bytes.begin(), token_bytes.end(), 0);
    token_bytes = std::move(replacement);
    return true;
}

inline std::vector<std::uint8_t> Get() {
    std::lock_guard lock{cache_mutex};
    return token_bytes;
}

inline bool HasToken() {
    std::lock_guard lock{cache_mutex};
    return !token_bytes.empty();
}

inline void Clear() {
    std::lock_guard lock{cache_mutex};
    std::fill(token_bytes.begin(), token_bytes.end(), 0);
    token_bytes.clear();
}

} // namespace Common::NextendoBaasTokenCache
