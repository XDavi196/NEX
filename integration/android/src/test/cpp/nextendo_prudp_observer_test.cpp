#include <cassert>
#include <cstdint>
#include <iterator>
#include <string>
#include <vector>

#include "core/internal_network/nextendo_prudp_observer.h"

namespace {

std::vector<std::uint8_t> MakePrudpSyn(bool acknowledgement) {
    // PRUDP-Lite SYN with support option (minor 5, supported functions 0x123456).
    std::vector<std::uint8_t> packet{
        0x80, static_cast<std::uint8_t>(acknowledgement ? 24 : 6), 0, 0,
        0x12, 0x34, 0x56, 0,
        static_cast<std::uint8_t>(acknowledgement ? 0x10 : 0x00), 0,
        1, 0,
        0, 4, 5, 0x56, 0x34, 0x12
    };
    if (acknowledgement) {
        packet.push_back(1);
        packet.push_back(16);
        for (std::uint8_t i = 0; i < 16; ++i) {
            packet.push_back(static_cast<std::uint8_t>(i + 1));
        }
    }
    return packet;
}

std::vector<std::uint8_t> MakeWebSocketFrame(const std::vector<std::uint8_t>& payload,
                                            bool masked) {
    assert(payload.size() < 126);
    std::vector<std::uint8_t> frame{0x82,
                                    static_cast<std::uint8_t>(payload.size() |
                                                              (masked ? 0x80 : 0))};
    const std::uint8_t mask[4]{0x11, 0x22, 0x33, 0x44};
    if (masked) {
        frame.insert(frame.end(), std::begin(mask), std::end(mask));
    }
    for (std::size_t i = 0; i < payload.size(); ++i) {
        frame.push_back(static_cast<std::uint8_t>(
            payload[i] ^ (masked ? mask[i % 4] : 0)));
    }
    return frame;
}

} // namespace

int main() {
    Common::NextendoPrudpObserver observer;
    observer.SetServerName("g22306d00-lp1.s.n.srv.nintendo.net");

    const std::string request = "GET / HTTP/1.1\r\nUpgrade: websocket\r\n\r\n";
    assert(observer.ObserveOutbound(
        reinterpret_cast<const std::uint8_t*>(request.data()), request.size()).empty());

    const auto syn = MakePrudpSyn(false);
    const auto masked = MakeWebSocketFrame(syn, true);
    assert(observer.ObserveOutbound(masked.data(), 3).empty());
    auto packets = observer.ObserveOutbound(masked.data() + 3, masked.size() - 3);
    assert(packets.size() == 1);
    assert(packets[0].server_name == "g22306d00-lp1.s.n.srv.nintendo.net");
    assert(packets[0].type == 0);
    assert(packets[0].flags == 0);
    assert(packets[0].source_type == 1);
    assert(packets[0].destination_type == 2);
    assert(packets[0].source_port == 0x34);
    assert(packets[0].destination_port == 0x56);
    assert(packets[0].packet_id == 1);
    assert(packets[0].minor_version == 5);
    assert(packets[0].supported_functions == 0x123456);
    assert(packets[0].payload_bytes == 0);

    const std::string response = "HTTP/1.1 101 Switching Protocols\r\n\r\n";
    assert(observer.ObserveInbound(
        reinterpret_cast<const std::uint8_t*>(response.data()), response.size()).empty());
    const auto syn_ack = MakePrudpSyn(true);
    const auto unmasked = MakeWebSocketFrame(syn_ack, false);
    packets = observer.ObserveInbound(unmasked.data(), unmasked.size());
    assert(packets.size() == 1);
    assert(packets[0].type == 0);
    assert(packets[0].flags == 1);
    assert(packets[0].payload_bytes == 0);

    observer.SetServerName("example.com");
    assert(observer.ObserveOutbound(masked.data(), masked.size()).empty());

    return 0;
}
