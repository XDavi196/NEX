// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace Common {

/**
 * Bounded, payload-blind observer for the SMM2 PRUDP-Lite handshake carried
 * inside the game's TLS/WebSocket stream.
 *
 * It records only SYN/CONNECT packet metadata (virtual station types/ports,
 * protocol version and feature mask). It never retains or emits PRUDP DATA,
 * RMC bodies, AuthenticationInfo, tickets, signatures, or JWT bytes.
 */
class NextendoPrudpObserver final {
public:
    struct PacketInfo {
        std::string server_name;
        std::uint8_t type{};
        std::uint16_t flags{};
        std::uint8_t source_type{};
        std::uint8_t destination_type{};
        std::uint8_t source_port{};
        std::uint8_t destination_port{};
        std::uint16_t packet_id{};
        std::uint8_t minor_version{};
        std::uint32_t supported_functions{};
        std::uint16_t payload_bytes{};
    };

    void SetServerName(std::string hostname) {
        std::lock_guard lock{mutex};
        server_name = std::move(hostname);
        enabled = IsTargetHost(server_name);
        Reset(outbound);
        Reset(inbound);
    }

    std::vector<PacketInfo> ObserveOutbound(const std::uint8_t* data, std::size_t size) {
        return Observe(outbound, data, size);
    }

    std::vector<PacketInfo> ObserveInbound(const std::uint8_t* data, std::size_t size) {
        return Observe(inbound, data, size);
    }

private:
    static constexpr std::size_t MaxBufferedBytes = 2 * 1024 * 1024;
    static constexpr std::size_t MaxFrameBytes = 1024 * 1024;
    static constexpr std::size_t HeaderBytes = 12;

    struct StreamState {
        bool http_headers_complete{};
        bool websocket_fragmented{};
        bool disabled{};
        std::vector<std::uint8_t> bytes;
        std::vector<std::uint8_t> fragmented_message;
    };

    static bool EndsWith(const std::string& value, const std::string& suffix) {
        return value.size() >= suffix.size() &&
               value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
    }

    static bool IsTargetHost(const std::string& hostname) {
        return hostname == "g22306d00-lp1.s.n.srv.nintendo.net" ||
               EndsWith(hostname, ".nextendo.network");
    }

    static void Wipe(std::vector<std::uint8_t>& value) {
        std::fill(value.begin(), value.end(), 0);
        value.clear();
    }

    static void Reset(StreamState& state) {
        Wipe(state.bytes);
        Wipe(state.fragmented_message);
        state.http_headers_complete = false;
        state.websocket_fragmented = false;
        state.disabled = false;
    }

    static std::uint16_t Read16LE(const std::uint8_t* data) {
        return static_cast<std::uint16_t>(data[0]) |
               static_cast<std::uint16_t>(static_cast<std::uint16_t>(data[1]) << 8);
    }

    static std::uint32_t Read32LE(const std::uint8_t* data) {
        return static_cast<std::uint32_t>(data[0]) |
               (static_cast<std::uint32_t>(data[1]) << 8) |
               (static_cast<std::uint32_t>(data[2]) << 16) |
               (static_cast<std::uint32_t>(data[3]) << 24);
    }

    static std::size_t FindHttpHeaderEnd(const std::vector<std::uint8_t>& data) {
        for (std::size_t i = 0; i + 3 < data.size(); ++i) {
            if (data[i] == '\r' && data[i + 1] == '\n' &&
                data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
        }
        return std::string::npos;
    }

    std::vector<PacketInfo> Observe(StreamState& state, const std::uint8_t* data,
                                    std::size_t size) {
        std::lock_guard lock{mutex};
        std::vector<PacketInfo> packets;
        if (!enabled || state.disabled || data == nullptr || size == 0) {
            return packets;
        }
        if (size > MaxBufferedBytes || state.bytes.size() > MaxBufferedBytes - size) {
            Reset(state);
            state.disabled = true;
            return packets;
        }

        state.bytes.insert(state.bytes.end(), data, data + size);
        if (!state.http_headers_complete) {
            const std::size_t end = FindHttpHeaderEnd(state.bytes);
            if (end == std::string::npos) {
                if (state.bytes.size() > 16 * 1024) {
                    Reset(state);
                    state.disabled = true;
                }
                return packets;
            }
            std::fill(state.bytes.begin(), state.bytes.begin() + static_cast<std::ptrdiff_t>(end), 0);
            state.bytes.erase(state.bytes.begin(), state.bytes.begin() + static_cast<std::ptrdiff_t>(end));
            state.http_headers_complete = true;
        }

        while (state.bytes.size() >= 2) {
            const std::uint8_t first = state.bytes[0];
            const std::uint8_t second = state.bytes[1];
            const std::uint8_t opcode = first & 0x0F;
            const bool final_frame = (first & 0x80) != 0;
            const bool masked = (second & 0x80) != 0;
            std::uint64_t payload_size = second & 0x7F;
            std::size_t header_size = 2;

            if (payload_size == 126) {
                if (state.bytes.size() < 4) {
                    break;
                }
                payload_size = (static_cast<std::uint64_t>(state.bytes[2]) << 8) |
                               static_cast<std::uint64_t>(state.bytes[3]);
                header_size = 4;
            } else if (payload_size == 127) {
                if (state.bytes.size() < 10) {
                    break;
                }
                payload_size = 0;
                for (std::size_t i = 2; i < 10; ++i) {
                    payload_size = (payload_size << 8) | state.bytes[i];
                }
                header_size = 10;
            }

            if (payload_size > MaxFrameBytes) {
                Reset(state);
                state.disabled = true;
                break;
            }

            std::array<std::uint8_t, 4> mask{};
            if (masked) {
                if (state.bytes.size() < header_size + mask.size()) {
                    break;
                }
                std::copy_n(state.bytes.begin() + static_cast<std::ptrdiff_t>(header_size),
                            mask.size(), mask.begin());
                header_size += mask.size();
            }

            const std::size_t frame_size = header_size + static_cast<std::size_t>(payload_size);
            if (state.bytes.size() < frame_size) {
                break;
            }

            std::vector<std::uint8_t> payload(static_cast<std::size_t>(payload_size));
            if (payload_size > 0) {
                std::copy_n(state.bytes.begin() + static_cast<std::ptrdiff_t>(header_size),
                            static_cast<std::size_t>(payload_size), payload.begin());
            }
            if (masked) {
                for (std::size_t i = 0; i < payload.size(); ++i) {
                    payload[i] ^= mask[i % mask.size()];
                }
            }

            if (opcode == 0x2) {
                Wipe(state.fragmented_message);
                state.fragmented_message = std::move(payload);
                state.websocket_fragmented = !final_frame;
                if (final_frame) {
                    AppendPrudpPackets(state.fragmented_message, packets);
                    Wipe(state.fragmented_message);
                }
            } else if (opcode == 0x0 && state.websocket_fragmented) {
                if (payload.size() > MaxBufferedBytes - state.fragmented_message.size()) {
                    Wipe(state.fragmented_message);
                    state.websocket_fragmented = false;
                } else {
                    state.fragmented_message.insert(state.fragmented_message.end(),
                                                     payload.begin(), payload.end());
                    if (final_frame) {
                        AppendPrudpPackets(state.fragmented_message, packets);
                        Wipe(state.fragmented_message);
                        state.websocket_fragmented = false;
                    }
                }
            }
            Wipe(payload);

            std::fill(state.bytes.begin(),
                      state.bytes.begin() + static_cast<std::ptrdiff_t>(frame_size), 0);
            state.bytes.erase(state.bytes.begin(),
                              state.bytes.begin() + static_cast<std::ptrdiff_t>(frame_size));
        }
        return packets;
    }

    void AppendPrudpPackets(const std::vector<std::uint8_t>& message,
                            std::vector<PacketInfo>& output) const {
        std::size_t offset = 0;
        while (message.size() - offset >= HeaderBytes) {
            const std::uint8_t* packet = message.data() + offset;
            if (packet[0] != 0x80) {
                return;
            }

            const std::size_t option_size = packet[1];
            const std::uint16_t payload_size = Read16LE(packet + 2);
            const std::size_t packet_size = HeaderBytes + option_size + payload_size;
            if (packet_size > message.size() - offset) {
                return;
            }

            const std::uint16_t type_and_flags = Read16LE(packet + 8);
            const std::uint8_t type = static_cast<std::uint8_t>(type_and_flags & 0x0F);
            if (type == 0 || type == 1) {
                std::uint8_t minor = 0;
                std::uint32_t supported = 0;
                std::size_t option_offset = HeaderBytes;
                const std::size_t option_end = HeaderBytes + option_size;
                while (option_offset + 2 <= option_end) {
                    const std::uint8_t option_id = packet[option_offset];
                    const std::size_t option_length = packet[option_offset + 1];
                    option_offset += 2;
                    if (option_length > option_end - option_offset) {
                        break;
                    }
                    if (option_id == 0 && option_length == 4) {
                        const std::uint32_t support = Read32LE(packet + option_offset);
                        minor = static_cast<std::uint8_t>(support & 0xFF);
                        supported = support >> 8;
                    }
                    option_offset += option_length;
                }

                output.push_back(PacketInfo{
                    .server_name = server_name,
                    .type = type,
                    .flags = static_cast<std::uint16_t>(type_and_flags >> 4),
                    .source_type = static_cast<std::uint8_t>(packet[4] >> 4),
                    .destination_type = static_cast<std::uint8_t>(packet[4] & 0x0F),
                    .source_port = packet[5],
                    .destination_port = packet[6],
                    .packet_id = Read16LE(packet + 10),
                    .minor_version = minor,
                    .supported_functions = supported,
                    .payload_bytes = payload_size,
                });
            }
            offset += packet_size;
        }
    }

    std::mutex mutex;
    bool enabled{};
    std::string server_name;
    StreamState outbound;
    StreamState inbound;
};

} // namespace Common
