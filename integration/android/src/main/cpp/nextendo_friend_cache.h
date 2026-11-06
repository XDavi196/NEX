// SPDX-FileCopyrightText: Copyright 2026 NEX contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

namespace Common::NextendoFriendCache {

inline constexpr std::size_t MaxFriends = 300;

struct Entry {
    std::uint64_t pid{};
    std::string username;
};

inline std::mutex cache_mutex;
inline std::vector<Entry> cache_entries;
inline std::atomic<std::uint64_t> current_player_pid{0};

/** Publishes the signed-in NEX PID to ACC without coupling HLE calls to Android/HTTP. */
inline void SetPlayerPid(std::uint64_t pid) {
    current_player_pid.store(pid, std::memory_order_release);
}

inline std::uint64_t GetPlayerPid() {
    return current_player_pid.load(std::memory_order_acquire);
}

inline void ClearPlayerPid() {
    current_player_pid.store(0, std::memory_order_release);
}

/** Atomically replaces the in-memory list with bounded, de-duplicated identity data. */
inline void Replace(std::vector<Entry> entries) {
    std::vector<Entry> normalized;
    normalized.reserve(std::min(entries.size(), MaxFriends));
    std::unordered_set<std::uint64_t> seen;

    for (auto& entry : entries) {
        if (entry.pid == 0 || entry.username.empty() || normalized.size() >= MaxFriends) {
            continue;
        }
        if (!seen.insert(entry.pid).second) {
            continue;
        }
        normalized.push_back(std::move(entry));
    }

    std::lock_guard lock{cache_mutex};
    cache_entries = std::move(normalized);
}

inline std::vector<Entry> Get() {
    std::lock_guard lock{cache_mutex};
    return cache_entries;
}

inline void Clear() {
    std::lock_guard lock{cache_mutex};
    cache_entries.clear();
}

} // namespace Common::NextendoFriendCache
