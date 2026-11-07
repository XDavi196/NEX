// SPDX-FileCopyrightText: Copyright 2026 NEX contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "nextendo_friend_cache.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace Common::NextendoFriendHle {

inline constexpr std::size_t NextendoFriendRecordV2Size = 0x220;
inline constexpr std::size_t NextendoFriendDetailedInfoV2Size = 0x800;
inline constexpr std::size_t NextendoFriendPresenceV2Size = 0x80;
inline constexpr std::size_t NextendoUserPresenceViewV2Size = 0xE8;

#pragma pack(push, 1)
struct NextendoUserPresence {
    std::array<std::uint8_t, 16> user_id{};
    std::int64_t last_time_online_timestamp{};
    std::uint32_t status{};
    std::uint8_t same_presence_group_application{};
    std::array<std::uint8_t, 3> unknown{};
    std::array<std::uint8_t, 0xC0> app_key_value{};
};
static_assert(sizeof(NextendoUserPresence) == 0xE0);

struct NextendoFriendInfo {
    std::array<std::uint8_t, 16> user_id{};
    std::uint64_t network_user_id{};
    std::array<char, 0x21> nickname{};
    std::array<std::uint8_t, 7> nickname_padding{};
    NextendoUserPresence presence{};
    std::uint8_t is_favourite{};
    std::uint8_t is_new{};
    std::array<std::uint8_t, 6> unknown{};
    std::uint8_t is_valid{};
    std::array<std::uint8_t, 0xD7> padding{};
};
static_assert(sizeof(NextendoFriendInfo) == 0x200);
static_assert(offsetof(NextendoFriendInfo, presence) == 0x40);
static_assert(offsetof(NextendoFriendInfo, is_favourite) == 0x120);
static_assert(offsetof(NextendoFriendInfo, is_valid) == 0x128);

struct NextendoProfileInfo {
    std::array<std::uint8_t, 16> user_uuid{};
    std::uint64_t network_user_id{};
    std::array<char, 0x21> nickname{};
    std::array<std::uint8_t, 7> padding{};
};
static_assert(sizeof(NextendoProfileInfo) == 0x40);
#pragma pack(pop)

/** Serializes only fields supplied by Nextendo; unknown identity fields stay zero. */
inline NextendoFriendInfo MakeNextendoFriendInfo(
    const NextendoFriendCache::Entry& entry,
    std::uint64_t requested_network_id = 0) {
    NextendoFriendInfo output{};
    output.network_user_id = requested_network_id != 0
        ? requested_network_id
        : entry.network_service_account_id;
    const auto name_length = std::min(entry.username.size(), output.nickname.size() - 1);
    std::memcpy(output.nickname.data(), entry.username.data(), name_length);
    output.presence.status = entry.presence_status;

    if (entry.presence_status == 2) {
        const auto app_field_size = std::min(
            entry.app_field_size, output.presence.app_key_value.size());
        std::copy_n(
            entry.app_field.begin(), app_field_size,
            output.presence.app_key_value.begin());
    }

    output.is_valid = 1;
    return output;
}

inline NextendoProfileInfo MakeNextendoProfileInfo(
    const NextendoFriendCache::Entry& entry,
    std::uint64_t requested_network_id = 0) {
    NextendoProfileInfo output{};
    output.network_user_id = requested_network_id != 0
        ? requested_network_id
        : entry.network_service_account_id;
    const auto name_length = std::min(entry.username.size(), output.nickname.size() - 1);
    std::memcpy(output.nickname.data(), entry.username.data(), name_length);
    return output;
}

/** Serializes the provisional qLaunch V2 list record without inventing a title. */
inline void WriteNextendoFriendRecordV2(
    std::uint8_t* record,
    const NextendoFriendCache::Entry& entry,
    std::uint64_t requested_network_id) {
    const auto legacy = MakeNextendoFriendInfo(entry, requested_network_id);
    std::memset(record, 0, NextendoFriendRecordV2Size);
    std::memcpy(record, &legacy, 0x40);
    std::memcpy(
        record + 0xE8,
        reinterpret_cast<const std::uint8_t*>(&legacy) + 0x40,
        0xE0);

    if (entry.presence_status == 2 && entry.application_id != 0) {
        std::memcpy(
            record + 0xE0,
            &entry.application_id,
            sizeof(entry.application_id));
    }
    record[0x21F] = 1;
}

/** Serializes the known part of the qLaunch detail page; unknown fields stay zero. */
inline void WriteNextendoFriendDetailedInfoV2(
    std::array<std::uint8_t, NextendoFriendDetailedInfoV2Size>& output,
    const NextendoFriendCache::Entry& entry,
    std::uint64_t requested_network_id) {
    output.fill(0);
    const auto legacy = MakeNextendoFriendInfo(entry, requested_network_id);
    std::memcpy(output.data(), &legacy, 0x40);
    output[0x608] = 1;

    if (entry.presence_status == 2 && entry.application_id != 0) {
        std::memcpy(
            output.data() + 0x570,
            &entry.application_id,
            sizeof(entry.application_id));
        std::memcpy(
            output.data() + 0x578,
            &entry.application_id,
            sizeof(entry.application_id));
    }
}

} // namespace Common::NextendoFriendHle
