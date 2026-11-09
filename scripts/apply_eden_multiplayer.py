from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = path.read_text(encoding="utf-8")
    if source.count(old) != 1:
        raise RuntimeError(f"{path}: expected one {label} anchor")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


def apply(eden: Path) -> None:
    bsd_cpp = eden / "src/core/hle/service/sockets/bsd.cpp"
    bsd_header = eden / "src/core/hle/service/sockets/bsd.h"

    replace_once(
        bsd_cpp, '#include <array>\n',
        '#include <algorithm>\n#include <array>\n#include <cstddef>\n#include <cstring>\n',
        "BSD standard includes",
    )
    replace_once(
        bsd_cpp,
        '#include "core/hle/service/sockets/bsd.h"\n',
        '#include "core/hle/service/sockets/bsd.h"\n#include "core/memory.h"\n',
        "BSD guest-memory include",
    )
    replace_once(
        bsd_header,
        "#include <memory>\n",
        "#include <map>\n#include <memory>\n",
        "socket option map include",
    )
    replace_once(
        bsd_header,
        "        bool is_connection_based = false;\n    };",
        "        bool is_connection_based = false;\n"
        "        std::map<std::pair<u32, u32>, std::vector<u8>> feigned_opts;\n"
        "    };",
        "saved socket options field",
    )
    replace_once(
        bsd_header,
        "    void SendTo(HLERequestContext& ctx);\n",
        "    void SendTo(HLERequestContext& ctx);\n"
        "    void RecvMMsg(HLERequestContext& ctx);\n"
        "    void SendMMsg(HLERequestContext& ctx);\n",
        "MMsg HLE declarations",
    )
    replace_once(
        bsd_cpp,
        '        {29, nullptr, "RecvMMsg"}, //3.0.0+\n'
        '        {30, nullptr, "SendMMsg"}, //3.0.0+',
        '        {29, &BSD::RecvMMsg, "RecvMMsg"}, //3.0.0+\n'
        '        {30, &BSD::SendMMsg, "SendMMsg"}, //3.0.0+',
        "MMsg command registration",
    )

    replace_once(
        bsd_cpp,
        "std::pair<s32, Errno> BSD::SocketImpl(Domain domain, Type type, Protocol protocol) {\n\n",
        "std::pair<s32, Errno> BSD::SocketImpl(Domain domain, Type type, Protocol protocol) {\n"
        "    // Eden's backend is IPv4-only; fail cleanly so NPLN can retry IPv4 after IPv6.\n"
        "    if (domain != Domain::INET && domain != Domain::Unspecified) {\n"
        "        LOG_WARNING(Service, \"[NEX] Unsupported socket domain={}, returning EAFNOSUPPORT\", static_cast<u32>(domain));\n"
        "        return {-1, Errno::AFNOSUPPORT};\n"
        "    }\n\n",
        "IPv6 fallback for sockets",
    )

    helper_anchor = """template <typename T>
void PutValue(std::span<u8> buffer, const T& t) {
    std::memcpy(buffer.data(), &t, (std::min)(sizeof(T), buffer.size()));
}

} // Anonymous namespace"""
    helper = """template <typename T>
void PutValue(std::span<u8> buffer, const T& t) {
    std::memcpy(buffer.data(), &t, (std::min)(sizeof(T), buffer.size()));
}

// Bounded NEX implementation of the nn::socket SendMMsg/RecvMMsg in/out ABI.
constexpr std::size_t MaxMMsgCount = 64;
constexpr std::size_t MaxMMsgIovCount = 16;
constexpr std::size_t MaxMMsgBufferBytes = 8 * 1024 * 1024;
constexpr std::size_t MaxMMsgControlBytes = 64 * 1024;

struct MMsgEntry {
    std::vector<u8> name;
    std::vector<std::vector<u8>> iov;
    std::vector<u8> control;
    u32 flags{};
    u32 length{};
};

bool MMsgReadU32(std::span<const u8>& data, u32& out) {
    if (data.size() < sizeof(u32)) return false;
    std::memcpy(&out, data.data(), sizeof(u32));
    data = data.subspan(sizeof(u32));
    return true;
}

bool MMsgReadU64(std::span<const u8>& data, u64& out) {
    if (data.size() < sizeof(u64)) return false;
    std::memcpy(&out, data.data(), sizeof(u64));
    data = data.subspan(sizeof(u64));
    return true;
}

bool MMsgReadBytes(std::span<const u8>& data, std::size_t count, std::vector<u8>& out) {
    if (count > data.size()) return false;
    out.assign(data.begin(), data.begin() + static_cast<std::ptrdiff_t>(count));
    data = data.subspan(count);
    return true;
}

bool MMsgDeserialize(std::span<const u8> data, s32 vlen, std::vector<MMsgEntry>& out) {
    if (data.empty() || data.front() != 0x8 || vlen < 0 ||
        static_cast<std::size_t>(vlen) > MaxMMsgCount) return false;
    data = data.subspan(1);
    out.clear();
    out.resize(static_cast<std::size_t>(vlen));
    std::size_t total_iov_bytes = 0;

    for (auto& msg : out) {
        u32 name_len{};
        if (!MMsgReadU32(data, name_len) ||
            (name_len != 0 && name_len != sizeof(SockAddrIn)) ||
            !MMsgReadBytes(data, name_len, msg.name)) return false;

        u32 iov_count{};
        if (!MMsgReadU32(data, iov_count) || iov_count > MaxMMsgIovCount) return false;
        msg.iov.resize(iov_count);
        for (auto& segment : msg.iov) {
            u64 iov_len{};
            if (!MMsgReadU64(data, iov_len) || iov_len > MaxMMsgBufferBytes ||
                iov_len > MaxMMsgBufferBytes - total_iov_bytes) return false;
            const auto length = static_cast<std::size_t>(iov_len);
            if (!MMsgReadBytes(data, length, segment)) return false;
            total_iov_bytes += length;
        }

        u32 control_len{};
        if (!MMsgReadU32(data, control_len) || control_len > MaxMMsgControlBytes ||
            !MMsgReadBytes(data, control_len, msg.control) ||
            !MMsgReadU32(data, msg.flags)) return false;
        u32 ignored_input_length{};
        if (!MMsgReadU32(data, ignored_input_length)) return false;
        msg.length = 0;
    }
    return true;
}

void MMsgAppendU32(std::vector<u8>& out, u32 value) {
    const auto* bytes = reinterpret_cast<const u8*>(&value);
    out.insert(out.end(), bytes, bytes + sizeof(value));
}

void MMsgAppendU64(std::vector<u8>& out, u64 value) {
    const auto* bytes = reinterpret_cast<const u8*>(&value);
    out.insert(out.end(), bytes, bytes + sizeof(value));
}

void MMsgSerialize(std::vector<u8>& out, const std::vector<MMsgEntry>& messages) {
    out.clear();
    out.push_back(0x8);
    for (const auto& msg : messages) {
        MMsgAppendU32(out, static_cast<u32>(msg.name.size()));
        out.insert(out.end(), msg.name.begin(), msg.name.end());
        MMsgAppendU32(out, static_cast<u32>(msg.iov.size()));
        for (const auto& segment : msg.iov) {
            MMsgAppendU64(out, static_cast<u64>(segment.size()));
            out.insert(out.end(), segment.begin(), segment.end());
        }
        MMsgAppendU32(out, static_cast<u32>(msg.control.size()));
        out.insert(out.end(), msg.control.begin(), msg.control.end());
        MMsgAppendU32(out, msg.flags);
        MMsgAppendU32(out, msg.length);
    }
}

// Buffer B is an in/out alias; read it directly and let ctx.WriteBuffer() copy back to B.
std::vector<u8> ReadMMsgBuffer(HLERequestContext& ctx) {
    if (!ctx.BufferDescriptorB().empty() && ctx.BufferDescriptorB()[0].Size() > 0) {
        const auto size = ctx.BufferDescriptorB()[0].Size();
        if (size > MaxMMsgBufferBytes) return {};
        std::vector<u8> raw(size);
        ctx.GetMemory().ReadBlock(ctx.BufferDescriptorB()[0].Address(), raw.data(), raw.size());
        return raw;
    }
    const auto fallback = ctx.ReadBuffer(0);
    if (fallback.size() > MaxMMsgBufferBytes) return {};
    return {fallback.begin(), fallback.end()};
}

} // Anonymous namespace"""
    replace_once(bsd_cpp, helper_anchor, helper, "MMsg parser/serializer")

    def replace_function(path: Path, signature: str, next_signature: str, body: str, label: str) -> None:
        source = path.read_text(encoding="utf-8")
        start = source.find(signature)
        if start < 0:
            raise RuntimeError(f"{path}: missing {label} function")
        end = source.find(next_signature, start + len(signature))
        if end < 0:
            raise RuntimeError(f"{path}: missing end marker for {label}")
        path.write_text(source[:start] + body + source[end:], encoding="utf-8")

    replace_function(
        bsd_cpp,
        "Errno BSD::GetSockOptImpl(",
        "Errno BSD::SetSockOptImpl(",
        """Errno BSD::GetSockOptImpl(s32 fd, u32 level, OptName optname, std::vector<u8>& optval) {
    if (!IsFileDescriptorValid(fd)) return Errno::BADF;
    if (!file_descriptors[fd]->socket) return Errno::BADF;

    Network::SocketBase* const socket = file_descriptors[fd]->socket.get();
    if (level == static_cast<u32>(SocketLevel::SOCKET) && optname == OptName::ERROR_) {
        auto [pending_err, getsockopt_err] = socket->GetPendingError();
        if (getsockopt_err == Network::Errno::SUCCESS) {
            const Errno translated_pending_err = Translate(pending_err);
            if (optval.size() != sizeof(Errno)) return Errno::INVAL;
            optval.resize(sizeof(Errno));
            PutValue(optval, translated_pending_err);
        }
        return Translate(getsockopt_err);
    }

    const auto key = std::make_pair(level, static_cast<u32>(optname));
    const auto& saved = file_descriptors[fd]->feigned_opts;
    if (const auto it = saved.find(key); it != saved.end()) {
        const std::size_t count = (std::min)(optval.size(), it->second.size());
        std::fill(optval.begin(), optval.end(), u8{0});
        std::copy_n(it->second.begin(), count, optval.begin());
        return Errno::SUCCESS;
    }
    std::fill(optval.begin(), optval.end(), u8{0});
    return Errno::SUCCESS;
}

""",
        "DEN2-compatible GetSockOpt",
    )
    replace_function(
        bsd_cpp,
        "Errno BSD::SetSockOptImpl(",
        "Errno BSD::ShutdownImpl(",
        """Errno BSD::SetSockOptImpl(s32 fd, u32 level, OptName optname, std::span<const u8> optval) {
    if (!IsFileDescriptorValid(fd)) return Errno::BADF;
    if (!file_descriptors[fd]->socket) return Errno::BADF;

    FileDescriptor& descriptor = *file_descriptors[fd];
    Network::SocketBase* const socket = descriptor.socket.get();
    const auto key = std::make_pair(level, static_cast<u32>(optname));
    const auto remember = [&] {
        descriptor.feigned_opts[key] = std::vector<u8>(optval.begin(), optval.end());
    };

    if (level != static_cast<u32>(SocketLevel::SOCKET) ||
        static_cast<u32>(optname) == 0x80000001) {
        remember();
        return Errno::SUCCESS;
    }

    if (optname == OptName::LINGER) {
        if (optval.size() != sizeof(Linger)) return Errno::INVAL;
        const auto linger = GetValue<Linger>(optval);
        if (linger.onoff != 0 && linger.onoff != 1) return Errno::INVAL;
        remember();
        return Translate(socket->SetLinger(linger.onoff != 0, linger.linger));
    }

    if (optval.size() != sizeof(u32)) {
        remember();
        return Errno::SUCCESS;
    }
    const u32 value = GetValue<u32>(optval);
    switch (optname) {
    case OptName::REUSEADDR:
        remember();
        return Translate(socket->SetReuseAddr(value != 0));
    case OptName::KEEPALIVE:
        remember();
        return Translate(socket->SetKeepAlive(value != 0));
    case OptName::BROADCAST:
        remember();
        return Translate(socket->SetBroadcast(value != 0));
    case OptName::SNDBUF:
        remember();
        return Translate(socket->SetSndBuf(value));
    case OptName::RCVBUF:
        remember();
        return Translate(socket->SetRcvBuf(value));
    case OptName::SNDTIMEO:
        remember();
        return Translate(socket->SetSndTimeo(value));
    case OptName::RCVTIMEO:
        remember();
        return Translate(socket->SetRcvTimeo(value));
    case OptName::NOSIGPIPE:
    default:
        remember();
        return Errno::SUCCESS;
    }
}

""",
        "DEN2-compatible SetSockOpt",
    )

    methods_anchor = "void BSD::DuplicateSocket(HLERequestContext& ctx) {"
    methods = """void BSD::SendMMsg(HLERequestContext& ctx) {
    IPC::RequestParser rp{ctx};
    const s32 fd = rp.Pop<s32>();
    const s32 vlen = rp.Pop<s32>();
    const u32 flags = rp.Pop<u32>();

    if (!IsFileDescriptorValid(fd)) {
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.Push<s32>(-1);
        rb.PushEnum(Errno::BADF);
        return;
    }
    auto raw_buffer = ReadMMsgBuffer(ctx);
    std::vector<MMsgEntry> messages;
    if (!MMsgDeserialize(raw_buffer, vlen, messages)) {
        LOG_ERROR(Service, "[NEX] SendMMsg fd={} vlen={}: invalid buffer", fd, vlen);
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.Push<s32>(-1);
        rb.PushEnum(Errno::INVAL);
        return;
    }

    s32 processed = 0;
    Errno last_errno = Errno::SUCCESS;
    for (auto& msg : messages) {
        std::size_t payload_size = 0;
        for (const auto& segment : msg.iov) payload_size += segment.size();
        std::vector<u8> payload;
        payload.reserve(payload_size);
        for (const auto& segment : msg.iov) {
            payload.insert(payload.end(), segment.begin(), segment.end());
        }
        const auto [sent, send_errno] = msg.name.empty()
            ? SendImpl(fd, flags | msg.flags, payload)
            : SendToImpl(fd, flags | msg.flags, payload, msg.name);
        if (send_errno != Errno::SUCCESS) {
            last_errno = send_errno;
            break;
        }
        msg.length = sent < 0 ? 0 : static_cast<u32>(sent);
        ++processed;
    }

    std::vector<u8> write_buffer;
    MMsgSerialize(write_buffer, messages);
    if (!write_buffer.empty()) ctx.WriteBuffer(write_buffer);
    IPC::ResponseBuilder rb{ctx, 4};
    rb.Push(ResultSuccess);
    rb.Push<s32>(last_errno == Errno::SUCCESS ? processed : -1);
    rb.PushEnum(last_errno);
}

void BSD::RecvMMsg(HLERequestContext& ctx) {
    IPC::RequestParser rp{ctx};
    const s32 fd = rp.Pop<s32>();
    const s32 vlen = rp.Pop<s32>();
    const u32 flags = rp.Pop<u32>();
    [[maybe_unused]] const u32 reserved = rp.Pop<u32>();
    struct TimeVal { u64 tv_sec; u64 tv_usec; };
    static_assert(sizeof(TimeVal) == 16);
    [[maybe_unused]] const auto timeout = rp.PopRaw<TimeVal>();

    if (!IsFileDescriptorValid(fd)) {
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.Push<s32>(-1);
        rb.PushEnum(Errno::BADF);
        return;
    }
    auto raw_buffer = ReadMMsgBuffer(ctx);
    std::vector<MMsgEntry> messages;
    if (!MMsgDeserialize(raw_buffer, vlen, messages)) {
        LOG_ERROR(Service, "[NEX] RecvMMsg fd={} vlen={}: invalid buffer", fd, vlen);
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.Push<s32>(-1);
        rb.PushEnum(Errno::INVAL);
        return;
    }

    s32 processed = 0;
    Errno last_errno = Errno::SUCCESS;
    for (auto& msg : messages) {
        std::size_t capacity = 0;
        for (const auto& segment : msg.iov) capacity += segment.size();
        std::vector<u8> received(capacity);
        s32 received_bytes = -1;
        std::vector<u8> remote_address(sizeof(SockAddrIn));
        Errno recv_errno = Errno::SUCCESS;
        if (!msg.name.empty() && !file_descriptors[fd]->is_connection_based) {
            std::tie(received_bytes, recv_errno) =
                RecvFromImpl(fd, flags | msg.flags, received, remote_address);
            if (recv_errno == Errno::SUCCESS && remote_address.size() <= msg.name.size()) {
                msg.name = std::move(remote_address);
            }
        } else {
            std::tie(received_bytes, recv_errno) = RecvImpl(fd, flags | msg.flags, received);
        }
        if (recv_errno != Errno::SUCCESS) {
            last_errno = recv_errno;
            break;
        }

        const std::size_t actual = received_bytes < 0
            ? 0 : (std::min)(static_cast<std::size_t>(received_bytes), capacity);
        std::size_t offset = 0;
        for (auto& segment : msg.iov) {
            if (offset < actual) {
                const std::size_t take = (std::min)(segment.size(), actual - offset);
                std::copy(received.begin() + static_cast<std::ptrdiff_t>(offset),
                          received.begin() + static_cast<std::ptrdiff_t>(offset + take),
                          segment.begin());
            }
            offset += segment.size();
        }
        msg.length = static_cast<u32>(actual);
        ++processed;
    }

    std::vector<u8> write_buffer;
    MMsgSerialize(write_buffer, messages);
    if (!write_buffer.empty()) ctx.WriteBuffer(write_buffer);
    IPC::ResponseBuilder rb{ctx, 4};
    rb.Push(ResultSuccess);
    rb.Push<s32>(last_errno == Errno::SUCCESS ? processed : -1);
    rb.PushEnum(last_errno);
}

"""
    replace_once(bsd_cpp, methods_anchor, methods + methods_anchor, "MMsg handlers")

    old_eventfd = """void BSD::EventFd(HLERequestContext& ctx) {
    IPC::RequestParser rp{ctx};
    const u64 initval = rp.Pop<u64>();
    const u32 flags = rp.Pop<u32>();

    LOG_WARNING(Service, "(STUBBED) called. initval={}, flags={}", initval, flags);

    BuildErrnoResponse(ctx, Errno::SUCCESS);
}"""
    new_eventfd = """void BSD::EventFd(HLERequestContext& ctx) {
    IPC::RequestParser rp{ctx};
    const u64 initval = rp.Pop<u64>();
    [[maybe_unused]] const u32 flags = rp.Pop<u32>();

    const s32 fd = FindFreeFileDescriptorHandle();
    if (fd < 0) {
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.Push<s32>(-1);
        rb.PushEnum(Errno::MFILE);
        return;
    }

    auto socket = std::make_shared<Network::Socket>();
    Network::Errno net_err = socket->Initialize(
        Network::Domain::INET, Network::Type::DGRAM, Network::Protocol::UDP);
    const Network::SockAddrIn loopback{
        .family = Network::Domain::INET,
        .ip = {127, 0, 0, 1},
        .portno = 0,
    };
    if (net_err == Network::Errno::SUCCESS) net_err = socket->Bind(loopback);
    Network::SockAddrIn bound{};
    if (net_err == Network::Errno::SUCCESS) {
        std::tie(bound, net_err) = socket->GetSockName();
    }
    if (net_err == Network::Errno::SUCCESS) {
        bound.ip = loopback.ip;
        net_err = socket->Connect(bound);
    }
    if (net_err != Network::Errno::SUCCESS) {
        LOG_ERROR(Service, "[NEX] Failed to create eventfd backing socket, errno={}",
                  static_cast<int>(net_err));
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.Push<s32>(-1);
        rb.PushEnum(Translate(net_err));
        return;
    }

    file_descriptors[fd] = FileDescriptor{};
    FileDescriptor& descriptor = *file_descriptors[fd];
    descriptor.socket = std::move(socket);
    descriptor.is_connection_based = true;
    if (initval > 0) {
        const auto seed = std::span<const u8>{
            reinterpret_cast<const u8*>(&initval), sizeof(initval)};
        (void)descriptor.socket->Send(seed, 0);
    }

    LOG_INFO(Service, "[NEX] Created socket-backed eventfd fd={} initval={}", fd, initval);
    IPC::ResponseBuilder rb{ctx, 4};
    rb.Push(ResultSuccess);
    rb.Push<s32>(fd);
    rb.PushEnum(Errno::SUCCESS);
}"""
    replace_once(bsd_cpp, old_eventfd, new_eventfd, "socket-backed eventfd")
