// SPDX-FileCopyrightText: Copyright 2026 NEX contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include "../../main/cpp/nextendo_friend_cache.h"

#include <cassert>
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
    entries.push_back(zero_pid);
    entries.push_back(empty_name);
    entries.push_back(invalid_status);

    cache::Replace(std::move(entries));
    const auto snapshot = cache::Get();
    assert(snapshot.size() == 1);
    assert(snapshot.front().pid == valid.pid);
    assert(snapshot.front().network_service_account_id == 0xA1);
    assert(snapshot.front().username == "Friend");
    assert(snapshot.front().presence_status == 2);
    assert(snapshot.front().application_id == 0x0100152000022000ULL);
    assert(snapshot.front().app_field_size == cache::MaxAppFieldBytes);
    assert(snapshot.front().app_field.back() == 0x7F);

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

    cache::Clear();
    assert(cache::Get().empty());
    // Clearing the friend snapshot and clearing the signed-in identity are separate operations.
    assert(cache::GetPlayerPid() == 1800000001);
    cache::ClearPlayerPid();
    assert(cache::GetPlayerPid() == 0);
    return 0;
}
