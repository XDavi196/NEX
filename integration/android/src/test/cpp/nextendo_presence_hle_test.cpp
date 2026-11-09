#include "integration/android/src/main/cpp/nextendo_presence_hle.h"

#include <cassert>
#include <cstdint>
#include <cstring>
#include <string_view>

namespace {
using Common::NextendoPresenceHle::AppField;
using Common::NextendoPresenceHle::AppFieldBytes;
using Common::NextendoPresenceHle::NormalizePresence;

void AppendPair(AppField& field, std::size_t& offset,
                std::string_view key, std::string_view value) {
    assert(offset + key.size() + value.size() + 2 <= field.size());
    std::memcpy(field.data() + offset, key.data(), key.size());
    offset += key.size();
    field[offset++] = 0;
    std::memcpy(field.data() + offset, value.data(), value.size());
    offset += value.size();
    field[offset++] = 0;
}
}

int main() {
    static_assert(AppFieldBytes == 0xC0);

    // Private SMM2/Splatoon session remains unchanged before both NAT vantage replies.
    AppField private_battle{};
    std::size_t offset = 0;
    AppendPair(private_battle, offset, "Mode", "cPrivate");
    AppendPair(private_battle, offset, "InGame", "0");
    const auto before_nat = private_battle;
    const auto pending = NormalizePresence(1U, private_battle, false);
    assert(pending.status == 1U);
    assert(!pending.private_battle_in_game_fixed);
    assert(private_battle == before_nat);

    // Once NAT is confirmed, only the InGame value byte changes; blob size/layout is stable.
    const auto joined = NormalizePresence(1U, private_battle, true);
    assert(joined.status == 2U);
    assert(joined.private_battle_in_game_fixed);
    const std::string_view joined_view(
        reinterpret_cast<const char*>(private_battle.data()), private_battle.size());
    assert(joined_view.find(std::string_view("InGame\0" "1\0", 9)) != std::string_view::npos);

    // Public rooms are never modified by the private-battle workaround.
    AppField public_room{};
    offset = 0;
    AppendPair(public_room, offset, "Mode", "Public");
    AppendPair(public_room, offset, "InGame", "0");
    const auto public_original = public_room;
    const auto public_result = NormalizePresence(1U, public_room, true);
    assert(public_result.status == 1U);
    assert(!public_result.private_battle_in_game_fixed);
    assert(public_room == public_original);

    // ARMS publishes Online while JoinMode 1..4 means an actual session is joinable.
    for (const auto mode : {"1", "2", "3", "4"}) {
        AppField arms{};
        offset = 0;
        AppendPair(arms, offset, "JoinMode", mode);
        const auto result = NormalizePresence(1U, arms, false);
        assert(result.status == 2U);
        assert(result.arms_session_active);
    }

    AppField arms_idle{};
    offset = 0;
    AppendPair(arms_idle, offset, "JoinMode", "0");
    const auto idle = NormalizePresence(1U, arms_idle, false);
    assert(idle.status == 1U);
    assert(!idle.arms_session_active);

    AppField empty{};
    assert(NormalizePresence(0U, empty, false).status == 1U);
    assert(NormalizePresence(3U, empty, false).status == 2U);

    return 0;
}
