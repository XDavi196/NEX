// SPDX-FileCopyrightText: Copyright 2026 NEX contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../main/cpp/nextendo_friend_cache.h"
#include "../../main/cpp/nextendo_friend_hle.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstring>
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

int main() {
    namespace cache = Common::NextendoFriendCache;

    cache::Clear();
    cache::ClearPlayerPid();
    assert(cache::Get().empty());
    assert(cache::GetPlayerPid() == 0);

    cache::SetPlayerPid(1800000001);
    assert(cache::GetPlayerPid() == 1800000001);

    cache::Entry valid{};
    valid.pid = 1800000002;
    valid.network_service_account_id = 0xA1;
    valid.username = "Friend";
    valid.presence_status = 2;
    valid.application_id = 0x0100152000022000ULL;
    valid.app_field[cache::MaxAppFieldBytes - 1] = 0x7F;
    valid.app_field_size = cache::MaxAppFieldBytes + 10;

    cache::Entry duplicate{};
    duplicate.pid = valid.pid;
    duplicate.username = "Duplicate";
    duplicate.presence_status = 1;

    cache::Entry no_nsa{};
    no_nsa.pid = 1800000005;
    no_nsa.username = "Friend without NSA";
    no_nsa.presence_status = 1;

    cache::Entry zero_pid{};
    zero_pid.username = "Zero PID";
    zero_pid.presence_status = 1;

    cache::Entry empty_name{};
    empty_name.pid = 1800000003;
    empty_name.presence_status = 1;

    cache::Entry invalid_status{};
    invalid_status.pid = 1800000004;
    invalid_status.username = "Invalid status";
    invalid_status.presence_status = 3;

    std::vector<cache::Entry> entries;
    entries.push_back(valid);
    entries.push_back(duplicate);
    entries.push_back(no_nsa);
    entries.push_back(zero_pid);
    entries.push_back(empty_name);
    entries.push_back(invalid_status);

    cache::Replace(std::move(entries));
    const auto snapshot = cache::Get();
    assert(snapshot.size() == 2);
    assert(snapshot.front().pid == valid.pid);
    const auto network_friends = cache::GetNetworkFriends();
    assert(network_friends.size() == 1);
    assert(network_friends.front().network_service_account_id == 0xA1);
    assert(snapshot.front().network_service_account_id == 0xA1);
    assert(snapshot.front().username == "Friend");
    assert(snapshot.front().presence_status == 2);
    assert(snapshot.front().application_id == 0x0100152000022000ULL);
    assert(snapshot.front().app_field_size == cache::MaxAppFieldBytes);
    assert(snapshot.front().app_field.back() == 0x7F);

    const auto friend_info = Common::NextendoFriendHle::MakeNextendoFriendInfo(valid);
    assert(friend_info.network_user_id == 0xA1);
    assert(friend_info.presence.status == 2);
    assert(friend_info.presence.last_time_online_timestamp == 0);
    assert(std::all_of(friend_info.user_id.begin(), friend_info.user_id.end(),
                       [](std::uint8_t value) { return value == 0; }));
    assert(friend_info.presence.app_key_value.back() == 0x7F);

    const auto profile_info = Common::NextendoFriendHle::MakeNextendoProfileInfo(valid);
    assert(profile_info.network_user_id == 0xA1);
    assert(std::all_of(profile_info.user_uuid.begin(), profile_info.user_uuid.end(),
                       [](std::uint8_t value) { return value == 0; }));

    auto online_friend = valid;
    online_friend.presence_status = 1;
    online_friend.application_id = 0;
    const auto online_info =
        Common::NextendoFriendHle::MakeNextendoFriendInfo(online_friend);
    assert(online_info.presence.status == 1);
    assert(online_info.presence.app_key_value.front() == 0);
    std::array<std::uint8_t, Common::NextendoFriendHle::NextendoFriendRecordV2Size> online_record{};
    Common::NextendoFriendHle::WriteNextendoFriendRecordV2(
        online_record.data(), online_friend, online_friend.network_service_account_id);
    std::uint64_t online_title_id = 1;
    std::memcpy(&online_title_id, online_record.data() + 0xE0, sizeof(online_title_id));
    assert(online_title_id == 0);
    assert(online_record[0x21F] == 1);

    std::array<std::uint8_t, Common::NextendoFriendHle::NextendoFriendRecordV2Size> playing_record{};
    Common::NextendoFriendHle::WriteNextendoFriendRecordV2(
        playing_record.data(), valid, valid.network_service_account_id);
    std::uint64_t playing_title_id = 0;
    std::memcpy(&playing_title_id, playing_record.data() + 0xE0, sizeof(playing_title_id));
    assert(playing_title_id == valid.application_id);
    assert(playing_record[0xE8 + offsetof(Common::NextendoFriendHle::NextendoUserPresence, status)] == 2);

    std::array<std::uint8_t, Common::NextendoFriendHle::NextendoFriendDetailedInfoV2Size> detail{};
    Common::NextendoFriendHle::WriteNextendoFriendDetailedInfoV2(
        detail, valid, valid.network_service_account_id);
    assert(detail[0x608] == 1);
    std::uint64_t detail_title_id = 0;
    std::memcpy(&detail_title_id, detail.data() + 0x570, sizeof(detail_title_id));
    assert(detail_title_id == valid.application_id);

    std::vector<cache::Entry> many;
    many.reserve(cache::MaxFriends + 5);
    for (std::size_t i = 0; i < cache::MaxFriends + 5; ++i) {
        cache::Entry entry{};
        entry.pid = 2000000000ULL + i;
        entry.username = "Player";
        entry.presence_status = 1;
        many.push_back(std::move(entry));
    }

    cache::Replace(std::move(many));
    const auto bounded = cache::Get();
    assert(bounded.size() == cache::MaxFriends);
    assert(bounded.front().pid == 2000000000ULL);
    assert(bounded.back().pid == 2000000000ULL + cache::MaxFriends - 1);
    assert(cache::GetNetworkFriends().empty());

    namespace baas = Common::NextendoBaasTokenCache;
    baas::Clear();
    assert(!baas::HasToken());
    assert(baas::Get().empty());

    const std::string valid_jws =
        "eyJhbGciOiJSUzI1NiJ9.eyJubmV4IjoibngyLnRlc3QifQ.c2lnbmF0dXJl";
    assert(baas::Set(
        reinterpret_cast<const std::uint8_t*>(valid_jws.data()), valid_jws.size()));
    assert(baas::HasToken());
    const auto copied_jws = baas::Get();
    assert(std::string(copied_jws.begin(), copied_jws.end()) == valid_jws);
    auto mutable_copy = baas::Get();
    mutable_copy.front() = 'X';
    assert(baas::Get().front() == static_cast<std::uint8_t>('e'));

    const std::string invalid_jws = "not-a-compact-jwt";
    assert(!baas::Set(
        reinterpret_cast<const std::uint8_t*>(invalid_jws.data()), invalid_jws.size()));
    assert(!baas::HasToken());
    assert(!baas::Set(nullptr, 0));
    baas::Clear();
    assert(baas::Get().empty());

    cache::Clear();
    assert(cache::Get().empty());
    // Clearing the friend snapshot and clearing the signed-in identity are separate operations.
    assert(cache::GetPlayerPid() == 1800000001);
    cache::ClearPlayerPid();
    assert(cache::GetPlayerPid() == 0);
    return 0;
}
