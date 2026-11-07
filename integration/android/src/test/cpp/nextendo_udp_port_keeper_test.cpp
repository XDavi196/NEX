#include <array>
#include <cassert>
#include <chrono>
#include <cstdint>

#include "../../main/cpp/nextendo_udp_port_keeper.h"

int main() {
    using Keeper = Common::NextendoUdpPortKeeper::Cache<int>;
    using Clock = Keeper::Clock;

    Keeper keeper;
    const Keeper::Address local{192, 0, 2, 44};
    const Keeper::Address other{192, 0, 2, 45};
    const auto start = Clock::time_point{};

    assert(keeper.Size() == 0);
    auto closed = keeper.Park(55963, local, 10, true, start);
    assert(closed.empty());
    assert(keeper.Size() == 1);

    auto adopted = keeper.Take(55963, local, start + std::chrono::milliseconds(100));
    assert(adopted.adopted.has_value());
    assert(adopted.adopted->handle == 10);
    assert(adopted.adopted->non_blocking);
    assert(adopted.close_handles.empty());
    assert(keeper.Size() == 0);

    keeper.Park(55963, local, 11, false, start);
    auto mismatch = keeper.Take(55963, other, start + std::chrono::milliseconds(100));
    assert(!mismatch.adopted.has_value());
    assert(mismatch.close_handles.size() == 1);
    assert(mismatch.close_handles.front() == 11);
    assert(keeper.Size() == 0);

    keeper.Park(55963, local, 12, false, start);
    auto expired = keeper.Take(55963, local, start + Keeper::HoldDuration);
    assert(!expired.adopted.has_value());
    assert(expired.close_handles.size() == 1);
    assert(expired.close_handles.front() == 12);

    keeper.Park(55963, local, 13, false, start);
    auto replaced = keeper.Park(55963, local, 14, true, start + std::chrono::milliseconds(1));
    assert(replaced.size() == 1);
    assert(replaced.front() == 13);
    auto duplicate_port = keeper.Take(55963, local, start + std::chrono::milliseconds(2));
    assert(duplicate_port.adopted.has_value());
    assert(duplicate_port.adopted->handle == 14);

    for (std::size_t index = 0; index < Keeper::MaxParkedSockets; ++index) {
        keeper.Park(
            static_cast<std::uint16_t>(55000 + index), local,
            static_cast<int>(100 + index), false, start);
    }
    assert(keeper.Size() == Keeper::MaxParkedSockets);
    auto evicted = keeper.Park(56000, local, 200, false, start + std::chrono::milliseconds(1));
    assert(evicted.size() == 1);
    assert(evicted.front() == 100);
    assert(keeper.Size() == Keeper::MaxParkedSockets);

    auto cleared = keeper.Clear();
    assert(cleared.size() == Keeper::MaxParkedSockets);
    assert(keeper.Size() == 0);
    assert(keeper.Clear().empty());
    return 0;
}
