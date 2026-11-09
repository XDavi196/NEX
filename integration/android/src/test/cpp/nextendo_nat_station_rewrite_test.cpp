#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "integration/android/src/main/cpp/nextendo_nat_station_rewrite.h"

namespace {

using Bytes = std::vector<std::uint8_t>;
using Common::NextendoNatStationRewrite::IPv4;

void Append16LE(Bytes& bytes, std::uint16_t value) {
    bytes.push_back(static_cast<std::uint8_t>(value));
    bytes.push_back(static_cast<std::uint8_t>(value >> 8));
}

void Append32LE(Bytes& bytes, std::uint32_t value) {
    bytes.push_back(static_cast<std::uint8_t>(value));
    bytes.push_back(static_cast<std::uint8_t>(value >> 8));
    bytes.push_back(static_cast<std::uint8_t>(value >> 16));
    bytes.push_back(static_cast<std::uint8_t>(value >> 24));
}

Bytes BuildFrame(std::uint8_t protocol, std::uint32_t method, std::string_view station,
                 bool masked = false) {
    Bytes body;
    Append16LE(body, static_cast<std::uint16_t>(station.size()));
    body.insert(body.end(), station.begin(), station.end());

    Bytes rmc;
    Append32LE(rmc, static_cast<std::uint32_t>(9 + body.size()));
    rmc.push_back(static_cast<std::uint8_t>(0x80U | protocol));
    Append32LE(rmc, 0x12345678U);
    Append32LE(rmc, method);
    rmc.insert(rmc.end(), body.begin(), body.end());

    Bytes prudp(12, 0);
    prudp[0] = 0x80;
    prudp[2] = static_cast<std::uint8_t>(rmc.size());
    prudp[3] = static_cast<std::uint8_t>(rmc.size() >> 8);
    prudp[8] = 0x02; // PRUDP DATA packet
    prudp.insert(prudp.end(), rmc.begin(), rmc.end());

    Bytes frame;
    frame.push_back(0x82); // FIN + binary
    if (prudp.size() <= 125) {
        frame.push_back(static_cast<std::uint8_t>((masked ? 0x80U : 0U) | prudp.size()));
    } else {
        frame.push_back(static_cast<std::uint8_t>((masked ? 0x80U : 0U) | 126U));
        frame.push_back(static_cast<std::uint8_t>(prudp.size() >> 8));
        frame.push_back(static_cast<std::uint8_t>(prudp.size()));
    }
    constexpr std::array<std::uint8_t, 4> mask{0x12, 0x34, 0x56, 0x78};
    if (masked) frame.insert(frame.end(), mask.begin(), mask.end());
    for (std::size_t i = 0; i < prudp.size(); ++i) {
        frame.push_back(masked ? static_cast<std::uint8_t>(prudp[i] ^ mask[i % 4]) : prudp[i]);
    }
    return frame;
}

Bytes DecodeFrame(const Bytes& frame) {
    if (frame.size() < 2 || (frame[0] & 0x0FU) != 0x02U) return {};
    const bool masked = (frame[1] & 0x80U) != 0;
    std::size_t offset = 2;
    std::size_t length = frame[1] & 0x7FU;
    if (length == 126) {
        if (frame.size() < offset + 2) return {};
        length = (static_cast<std::size_t>(frame[offset]) << 8) | frame[offset + 1];
        offset += 2;
    } else if (length == 127) {
        return {};
    }
    std::array<std::uint8_t, 4> mask{};
    if (masked) {
        if (frame.size() < offset + 4) return {};
        for (std::size_t i = 0; i < 4; ++i) mask[i] = frame[offset + i];
        offset += 4;
    }
    if (frame.size() != offset + length) return {};
    Bytes payload(frame.begin() + static_cast<std::ptrdiff_t>(offset), frame.end());
    if (masked) {
        for (std::size_t i = 0; i < payload.size(); ++i) payload[i] ^= mask[i % 4];
    }
    return payload;
}

std::string ExtractStation(const Bytes& frame) {
    const auto payload = DecodeFrame(frame);
    if (payload.size() < 25 || payload[0] != 0x80) return {};
    const std::size_t rmc_offset = 12 + payload[1];
    const std::size_t rmc_size = payload[2] |
        (static_cast<std::size_t>(payload[3]) << 8);
    if (rmc_offset + rmc_size > payload.size() || rmc_size < 15) return {};
    const std::size_t body_offset = rmc_offset + 13;
    if (body_offset + 2 > payload.size()) return {};
    const std::size_t station_size = payload[body_offset] |
        (static_cast<std::size_t>(payload[body_offset + 1]) << 8);
    if (body_offset + 2 + station_size > payload.size()) return {};
    return std::string(
        reinterpret_cast<const char*>(payload.data() + body_offset + 2), station_size);
}

bool TestSupportedMethods() {
    constexpr std::string_view station =
        "prudp:/address=192.168.1.20;port=1234;natf=1;natm=1";
    constexpr IPv4 public_ip{8, 8, 8, 8};
    constexpr std::array<std::pair<std::uint8_t, std::uint32_t>, 6> methods{{
        {0x0B, 0x01}, {0x0B, 0x04}, {0x0B, 0x07},
        {0x6D, 0x26}, {0x6D, 0x27}, {0x6D, 0x28}
    }};
    for (const auto& [protocol, method] : methods) {
        const auto input = BuildFrame(protocol, method, station);
        Bytes output;
        std::size_t count = 0;
        if (!Common::NextendoNatStationRewrite::TryFixupStationAddress(
                input, public_ip, output, &count)) return false;
        if (count != 1 || ExtractStation(output).find("address=8.8.8.8;") == std::string::npos) {
            return false;
        }
        const auto rewritten = ExtractStation(output);
        if (rewritten.find(";port=1234;natf=1;natm=1") == std::string::npos) return false;
    }
    return true;
}

bool TestMaskedLongFrame() {
    std::string station = "prudp:/address=10.0.0.12;port=20000;natf=1;natm=1;extra=";
    station += std::string(100, 'x');
    const auto input = BuildFrame(0x6D, 0x28, station, true);
    Bytes output;
    if (!Common::NextendoNatStationRewrite::TryFixupStationAddress(
            input, IPv4{1, 1, 1, 1}, output)) return false;
    return ExtractStation(output).find("prudp:/address=1.1.1.1;") == 0;
}

bool TestNoRewriteForLocalOrUnsupportedTraffic() {
    constexpr IPv4 public_ip{8, 8, 4, 4};
    Bytes output;

    const auto local = BuildFrame(
        0x6D, 0x28, "prudp:/address=192.168.1.20;port=1234;natf=0;natm=0");
    if (Common::NextendoNatStationRewrite::TryFixupStationAddress(local, public_ip, output)) {
        return false;
    }

    const auto unsupported = BuildFrame(
        0x6D, 0x29, "prudp:/address=192.168.1.20;port=1234;natf=1;natm=1");
    if (Common::NextendoNatStationRewrite::TryFixupStationAddress(
            unsupported, public_ip, output)) return false;

    const auto http = Bytes{'G', 'E', 'T', ' '};
    return !Common::NextendoNatStationRewrite::TryFixupStationAddress(http, public_ip, output);
}

bool TestNncsReplyCapture() {
    using namespace Common::NextendoNatStationRewrite;
    ClearObservedExternalIPv4();

    Bytes primary(16, 0);
    // NNCS reply: test id 101, external port 41000, external IP 8.8.8.8,
    // first responder 9.9.9.9.
    primary[3] = 101;
    primary[6] = 0xA0;
    primary[7] = 0x28;
    primary[8] = 8;
    primary[9] = 8;
    primary[10] = 8;
    primary[11] = 8;
    primary[12] = 9;
    primary[13] = 9;
    primary[14] = 9;
    primary[15] = 9;

    if (ObserveNncsReply(10025, 55000, primary)) return false;
    if (GetObservedExternalIPv4().has_value()) return false;

    Bytes secondary = primary;
    // Second vantage is a distinct NNCS responder at 4.4.4.4.
    secondary[12] = 4;
    secondary[13] = 4;
    secondary[14] = 4;
    secondary[15] = 4;
    if (!ObserveNncsReply(10125, 55000, secondary)) return false;

    const auto value = GetObservedExternalIPv4();
    if (!value || *value != IPv4{8, 8, 8, 8}) return false;

    ClearObservedExternalIPv4();
    if (ObserveNncsReply(9999, 55000, primary)) return false;
    if (ObserveNncsReply(10025, 55000, Bytes{1, 2, 3})) return false;
    Bytes wrong_test = primary;
    wrong_test[3] = 100;
    if (ObserveNncsReply(10025, 55000, wrong_test)) return false;
    Bytes private_address = primary;
    private_address[8] = 192;
    private_address[9] = 168;
    private_address[10] = 1;
    private_address[11] = 20;
    if (ObserveNncsReply(10025, 55000, private_address)) return false;

    ClearObservedExternalIPv4();
    Bytes same_responder = primary;
    same_responder[12] = 9;
    same_responder[13] = 9;
    same_responder[14] = 9;
    same_responder[15] = 9;
    if (ObserveNncsReply(10025, 55000, primary)) return false;
    if (ObserveNncsReply(10125, 55000, same_responder)) return false;

    ClearObservedExternalIPv4();
    Bytes local_port_mismatch = secondary;
    if (ObserveNncsReply(10025, 55000, primary)) return false;
    if (ObserveNncsReply(10125, 55001, local_port_mismatch)) return false;
    return !GetObservedExternalIPv4().has_value();
}

bool TestObservedIpv4Validation() {
    using namespace Common::NextendoNatStationRewrite;
    if (ParsePublicIPv4("192.168.1.2").has_value()) return false;
    if (ParsePublicIPv4("203.0.113.5").has_value()) return false;
    const auto value = ParsePublicIPv4("8.8.8.8");
    return value.has_value() && *value == IPv4{8, 8, 8, 8};
}

} // namespace

int main() {
    if (!TestSupportedMethods()) return 1;
    if (!TestMaskedLongFrame()) return 2;
    if (!TestNoRewriteForLocalOrUnsupportedTraffic()) return 3;
    if (!TestObservedIpv4Validation()) return 4;
    if (!TestNncsReplyCapture()) return 5;
    return 0;
}
