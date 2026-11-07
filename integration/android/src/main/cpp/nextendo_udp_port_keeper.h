// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <map>
#include <mutex>
#include <optional>
#include <vector>

namespace Common::NextendoUdpPortKeeper {

/**
 * Short-lived cache for unconnected, bound UDP sockets.
 *
 * Some Switch P2P stacks close the socket used for NAT probing and reopen the
 * same local port for peer traffic. Keeping the host socket alive preserves the
 * external NAT mapping observed by the probe server. The caller remains
 * responsible for restricting this cache to the active Nextendo session.
 */
template <typename Handle>
class Cache {
public:
    using Clock = std::chrono::steady_clock;
    using TimePoint = Clock::time_point;
    using Address = std::array<std::uint8_t, 4>;

    static constexpr std::size_t MaxParkedSockets = 8;
    static constexpr auto HoldDuration = std::chrono::seconds(5);

    struct Entry {
        Handle handle;
        Address address;
        bool non_blocking;
        TimePoint expires_at;
    };

    struct AdoptResult {
        std::optional<Entry> adopted;
        std::vector<Handle> close_handles;
    };

    /** Park one socket and return older handles that the caller must close. */
    std::vector<Handle> Park(
        std::uint16_t port, const Address& address, Handle handle,
        bool non_blocking, TimePoint now = Clock::now()) {
        std::lock_guard lock{mutex};
        std::vector<Handle> close_handles;
        CollectExpiredLocked(now, close_handles);

        if (port == 0) {
            close_handles.push_back(handle);
            return close_handles;
        }

        const auto existing = parked.find(port);
        if (existing != parked.end()) {
            close_handles.push_back(existing->second.handle);
            parked.erase(existing);
        }

        if (parked.size() >= MaxParkedSockets) {
            const auto oldest = std::min_element(
                parked.begin(), parked.end(),
                [](const auto& left, const auto& right) {
                    return left.second.expires_at < right.second.expires_at;
                });
            if (oldest != parked.end()) {
                close_handles.push_back(oldest->second.handle);
                parked.erase(oldest);
            }
        }

        parked.emplace(port, Entry{handle, address, non_blocking, now + HoldDuration});
        return close_handles;
    }

    /** Adopt the parked socket for the same local port and IPv4 bind address. */
    AdoptResult Take(
        std::uint16_t port, const Address& address, TimePoint now = Clock::now()) {
        std::lock_guard lock{mutex};
        AdoptResult result;
        CollectExpiredLocked(now, result.close_handles);

        const auto found = parked.find(port);
        if (found == parked.end()) {
            return result;
        }

        if (found->second.address == address) {
            result.adopted = found->second;
        } else {
            // Do not leave an incompatible socket holding the requested local port.
            result.close_handles.push_back(found->second.handle);
        }
        parked.erase(found);
        return result;
    }

    /** Return every held socket so the caller can close them on emulator shutdown. */
    std::vector<Handle> Clear() {
        std::lock_guard lock{mutex};
        std::vector<Handle> close_handles;
        close_handles.reserve(parked.size());
        for (const auto& [port, entry] : parked) {
            (void)port;
            close_handles.push_back(entry.handle);
        }
        parked.clear();
        return close_handles;
    }

    std::size_t Size() const {
        std::lock_guard lock{mutex};
        return parked.size();
    }

private:
    void CollectExpiredLocked(TimePoint now, std::vector<Handle>& close_handles) {
        for (auto iterator = parked.begin(); iterator != parked.end();) {
            if (iterator->second.expires_at <= now) {
                close_handles.push_back(iterator->second.handle);
                iterator = parked.erase(iterator);
            } else {
                ++iterator;
            }
        }
    }

    mutable std::mutex mutex;
    std::map<std::uint16_t, Entry> parked;
};

} // namespace Common::NextendoUdpPortKeeper
