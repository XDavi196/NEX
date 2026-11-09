// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace Common::NextendoPresenceHle {

inline constexpr std::size_t AppFieldBytes = 0xC0;
using AppField = std::array<std::uint8_t, AppFieldBytes>;

struct NormalizedPresence {
    std::uint32_t status{};
    bool private_battle_in_game_fixed{};
    bool arms_session_active{};
};

inline std::size_t FindTokenEnd(const AppField& field, std::size_t start) {
    while (start < field.size() && field[start] != 0) ++start;
    return start;
}

/** Den2/Splatoon 2 fix: only flip InGame=0 for cPrivate after real NAT confirmation. */
inline bool FixupPrivateBattleInGameFlag(AppField& field, bool nat_confirmed) {
    if (!nat_confirmed) return false;

    const auto view = std::string_view(
        reinterpret_cast<const char*>(field.data()), field.size());
    bool private_mode = false;
    std::size_t in_game_value_offset = field.size();
    bool in_game_is_zero = false;

    std::size_t pos = 0;
    while (pos < view.size()) {
        const auto key_end = view.find('\0', pos);
        const auto key_limit = key_end == std::string_view::npos ? view.size() : key_end;
        if (key_limit == pos) break;
        const auto key = view.substr(pos, key_limit - pos);
        pos = key_limit + 1;
        if (pos >= view.size()) break;

        const auto value_end = view.find('\0', pos);
        const auto value_limit = value_end == std::string_view::npos ? view.size() : value_end;
        const auto value = view.substr(pos, value_limit - pos);
        if (key == "Mode") private_mode = value == "cPrivate";
        if (key == "InGame") {
            in_game_value_offset = pos;
            in_game_is_zero = value == "0";
        }
        pos = value_limit + 1;
    }

    if (!private_mode || in_game_value_offset >= field.size() || !in_game_is_zero) {
        return false;
    }
    field[in_game_value_offset] = static_cast<std::uint8_t>('1');
    return true;
}

/** Den2/ARMS fix: JoinMode 1..4 means the title has a live session. */
inline bool IsArmsSessionActive(const AppField& field) {
    const auto view = std::string_view(
        reinterpret_cast<const char*>(field.data()), field.size());
    std::size_t pos = 0;
    while (pos < view.size()) {
        const auto key_end = view.find('\0', pos);
        const auto key_limit = key_end == std::string_view::npos ? view.size() : key_end;
        if (key_limit == pos) break;
        const auto key = view.substr(pos, key_limit - pos);
        pos = key_limit + 1;
        if (pos >= view.size()) break;

        const auto value_end = view.find('\0', pos);
        const auto value_limit = value_end == std::string_view::npos ? view.size() : value_end;
        const auto value = view.substr(pos, value_limit - pos);
        pos = value_limit + 1;

        if (key == "JoinMode") {
            return value == "1" || value == "2" || value == "3" || value == "4";
        }
    }
    return false;
}

inline NormalizedPresence NormalizePresence(
    std::uint32_t status,
    AppField& field,
    bool nat_confirmed
) {
    NormalizedPresence result{
        .status = std::clamp<std::uint32_t>(status, 1U, 2U),
        .private_battle_in_game_fixed = false,
        .arms_session_active = false
    };

    result.private_battle_in_game_fixed =
        FixupPrivateBattleInGameFlag(field, nat_confirmed);
    result.arms_session_active = IsArmsSessionActive(field);

    if (result.private_battle_in_game_fixed || result.arms_session_active) {
        result.status = 2U;
    }
    return result;
}

} // namespace Common::NextendoPresenceHle
