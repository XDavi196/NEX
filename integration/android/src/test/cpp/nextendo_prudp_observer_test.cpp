#include <cassert>
#include <cstdint>
#include <iterator>
#include <string>
#include <vector>

#include "../../main/cpp/nextendo_prudp_observer.h"

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

std::vector<std::uint8_t> MakePrudpConnect() {
    // CONNECT carries the support option and the 16-byte lite-signature option.
    return {
        0x80, 24, 0, 0,
        0x12, 0x34, 0x56, 0,
        0x01, 0,
        2, 0,
        0, 4, 5, 0x56, 0x34, 0x12,
        128, 16,
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
}

std::vector<std::uint8_t> MakeWebSocketFrame(const std::vector<std::uint8_t>& payload,
                                            bool masked, bool final_frame = true,
                                            std::uint8_t opcode = 0x2) {
    std::vector<std::uint8_t> frame{
        static_cast<std::uint8_t>((final_frame ? 0x80 : 0x00) | opcode)};
    const std::uint8_t mask[4]{0x11, 0x22, 0x33, 0x44};
    const std::uint8_t mask_bit = masked ? 0x80 : 0x00;
    if (payload.size() < 126) {
        frame.push_back(static_cast<std::uint8_t>(payload.size() | mask_bit));
    } else if (payload.size() <= 0xFFFF) {
        frame.push_back(static_cast<std::uint8_t>(126 | mask_bit));
        frame.push_back(static_cast<std::uint8_t>(payload.size() >> 8));
        frame.push_back(static_cast<std::uint8_t>(payload.size()));
    } else {
        frame.push_back(static_cast<std::uint8_t>(127 | mask_bit));
        const auto length = static_cast<std::uint64_t>(payload.size());
        for (int shift = 56; shift >= 0; shift -= 8) {
            frame.push_back(static_cast<std::uint8_t>(length >> shift));
        }
    }
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
    Common::NextendoPrudpObserver::ClearRecentPackets();
    Common::NextendoPrudpObserver observer;
    observer.SetServerName("g22306d00-lp1.s.n.srv.nintendo.net");
    auto events = Common::NextendoPrudpObserver::GetRecentEvents();
    assert(events.size() == 1);
    assert(events[0].stage == "SNI");
    assert(events[0].status == "configured");
    observer.RecordTlsHandshake(true, false, true);
    events = Common::NextendoPrudpObserver::GetRecentEvents();
    assert(events.size() == 2);
    assert(events.back().stage == "TLS");
    assert(events.back().status == "success");
    assert(events.back().verification_skipped);
    observer.RecordTlsHandshake(false, true, false);
    events = Common::NextendoPrudpObserver::GetRecentEvents();
    assert(events.size() == 3);
    assert(events.back().status == "pending");
    assert(!events.back().verification_skipped);

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
    assert(packets[0].from_client);

    auto recent = Common::NextendoPrudpObserver::GetRecentPackets();
    assert(recent.size() == 1);
    assert(recent[0].from_client);
    assert(recent[0].server_name == packets[0].server_name);
    assert(recent[0].supported_functions == 0x123456);

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
    assert(!packets[0].from_client);

    recent = Common::NextendoPrudpObserver::GetRecentPackets();
    assert(recent.size() == 2);
    assert(recent[1].from_client == false);
    assert(recent[1].type == 0);

    // A CONNECT request is captured as metadata without retaining its 16-byte signature.
    observer.SetServerName("g22306d00-lp1.s.n.srv.nintendo.net");
    assert(observer.ObserveOutbound(
        reinterpret_cast<const std::uint8_t*>(request.data()), request.size()).empty());
    const auto connect = MakePrudpConnect();
    const auto connect_frame = MakeWebSocketFrame(connect, true);
    packets = observer.ObserveOutbound(connect_frame.data(), connect_frame.size());
    assert(packets.size() == 1);
    assert(packets[0].type == 1);
    assert(packets[0].minor_version == 5);
    assert(packets[0].supported_functions == 0x123456);
    assert(packets[0].payload_bytes == 0);

    observer.SetServerName("example.com");
    assert(observer.ObserveOutbound(masked.data(), masked.size()).empty());

    recent = Common::NextendoPrudpObserver::GetRecentPackets();
    assert(recent.size() == 3);
    Common::NextendoPrudpObserver::ClearRecentPackets();
    assert(Common::NextendoPrudpObserver::GetRecentPackets().empty());

    // The process-wide diagnostic ring must remain bounded under repeated handshakes.
    for (std::size_t i = 0; i < 40; ++i) {
        observer.SetServerName("g22306d00-lp1.s.n.srv.nintendo.net");
        observer.RecordTlsHandshake(true, false, true);
        assert(observer.ObserveOutbound(
            reinterpret_cast<const std::uint8_t*>(request.data()), request.size()).empty());
        (void)observer.ObserveOutbound(masked.data(), masked.size());
    }
    events = Common::NextendoPrudpObserver::GetRecentEvents();
    assert(events.size() == 16);
    assert(events.back().stage == "TLS");
    recent = Common::NextendoPrudpObserver::GetRecentPackets();
    assert(recent.size() == 32);
    assert(recent.back().from_client);
    assert(recent.back().type == 0);
    Common::NextendoPrudpObserver::ClearRecentPackets();

    // Split a binary WebSocket message across an initial and continuation frame.
    Common::NextendoPrudpObserver framing_observer;
    framing_observer.SetServerName("g22306d00-lp1.s.n.srv.nintendo.net");
    assert(framing_observer.ObserveOutbound(
        reinterpret_cast<const std::uint8_t*>(request.data()), request.size()).empty());
    const auto fragment_packet = MakePrudpSyn(false);
    const std::size_t split = 7;
    const std::vector<std::uint8_t> first_part(
        fragment_packet.begin(), fragment_packet.begin() + static_cast<std::ptrdiff_t>(split));
    const std::vector<std::uint8_t> second_part(
        fragment_packet.begin() + static_cast<std::ptrdiff_t>(split), fragment_packet.end());
    const auto first_fragment = MakeWebSocketFrame(first_part, true, false, 0x2);
    const auto continuation = MakeWebSocketFrame(second_part, true, true, 0x0);
    assert(framing_observer.ObserveOutbound(
        first_fragment.data(), first_fragment.size()).empty());
    packets = framing_observer.ObserveOutbound(continuation.data(), continuation.size());
    assert(packets.size() == 1);
    assert(packets[0].type == 0);

    // A 16-bit extended WebSocket length must still expose only PRUDP metadata.
    framing_observer.SetServerName("g22306d00-lp1.s.n.srv.nintendo.net");
    assert(framing_observer.ObserveOutbound(
        reinterpret_cast<const std::uint8_t*>(request.data()), request.size()).empty());
    auto extended_packet = MakePrudpSyn(false);
    constexpr std::uint16_t extended_payload_size = 130;
    extended_packet[2] = static_cast<std::uint8_t>(extended_payload_size);
    extended_packet[3] = static_cast<std::uint8_t>(extended_payload_size >> 8);
    extended_packet.insert(extended_packet.end(), extended_payload_size, 0x5A);
    const auto extended_frame = MakeWebSocketFrame(extended_packet, true);
    packets = framing_observer.ObserveOutbound(extended_frame.data(), extended_frame.size());
    assert(packets.size() == 1);
    assert(packets[0].type == 0);
    assert(packets[0].payload_bytes == extended_payload_size);
    Common::NextendoPrudpObserver::ClearRecentPackets();

    return 0;
}
