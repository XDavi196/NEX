#!/usr/bin/env python3
"""Apply the NEX Android integration overlay to the pinned Eden source checkout."""

from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path

EXPECTED_EDEN_COMMIT = "8e373eb714aa01117ec885680477501cdb572112"
NEX_SOURCE = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one occurrence of target text, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("eden_source", type=Path)
    args = parser.parse_args()

    eden = args.eden_source.resolve()
    if not (eden / ".git").exists():
        raise RuntimeError(f"{eden} is not a Git checkout")

    commit = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=eden, text=True
    ).strip()
    if commit != EXPECTED_EDEN_COMMIT:
        raise RuntimeError(
            f"Wrong Eden source revision: expected {EXPECTED_EDEN_COMMIT}, got {commit}"
        )

    app_dir = eden / "src/android/app"
    kotlin_destination = app_dir / "src/main/java/com/nex/nx/nextendo"
    kotlin_source = NEX_SOURCE / "integration/android/src/main/java/com/nex/nx/nextendo"
    if not kotlin_source.is_dir():
        raise RuntimeError(f"Missing NEX Kotlin sources: {kotlin_source}")

    kotlin_destination.mkdir(parents=True, exist_ok=True)
    for source in sorted(kotlin_source.glob("*.kt")):
        shutil.copy2(source, kotlin_destination / source.name)

    test_source = NEX_SOURCE / "integration/android/src/test/java/com/nex/nx/nextendo"
    test_destination = app_dir / "src/test/java/com/nex/nx/nextendo"
    if not test_source.is_dir():
        raise RuntimeError(f"Missing NEX Android unit tests: {test_source}")
    test_destination.mkdir(parents=True, exist_ok=True)
    for source in sorted(test_source.glob("*Test.kt")):
        shutil.copy2(source, test_destination / source.name)


    # Add the NEX native friend-cache API to Eden's Kotlin/JNI boundary.
    native_library = app_dir / "src/main/java/org/yuzu/yuzu_emu/NativeLibrary.kt"
    replace_once(
        native_library,
        "    external fun getAllUsers(): Array<String>?\n",
        "    external fun updateNextendoFriendCache(pids: LongArray, nsas: LongArray, appIds: LongArray, usernames: Array<String>, statuses: IntArray, appFields: Array<ByteArray>)\n"
        "    external fun clearNextendoFriendCache()\n"
        "    external fun updateNextendoIdentity(pid: Long)\n"
        "    external fun clearNextendoIdentity()\n"
        "    external fun updateNextendoBaasIdToken(idToken: ByteArray)\n"
        "    external fun clearNextendoBaasIdToken()\n"
        "    external fun updateNextendoDnsRedirect(hostname: String, ipv4: String): Boolean\n"
        "    external fun clearNextendoDnsRedirects()\n"
        "    external fun clearNextendoObservedExternalIpv4()\n"
        "    external fun getNextendoPrudpDiagnostics(): Array<String>?\n"
        "    external fun clearNextendoPrudpDiagnostics()\n"
        "    external fun getAllUsers(): Array<String>?\n",
    )

    # Copy the NEX-owned bounded friend cache and the serializers used by friend IPC.
    native_header_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_friend_cache.h"
    native_header_destination = (
        eden / "src/core/hle/service/friend/nextendo_friend_cache.h"
    )
    if not native_header_source.is_file():
        raise RuntimeError(f"Missing NEX native friend cache header: {native_header_source}")
    native_header_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(native_header_source, native_header_destination)

    friend_hle_header_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_friend_hle.h"
    friend_hle_header_destination = (
        eden / "src/core/hle/service/friend/nextendo_friend_hle.h"
    )
    if not friend_hle_header_source.is_file():
        raise RuntimeError(f"Missing NEX friend HLE serializer header: {friend_hle_header_source}")
    shutil.copy2(friend_hle_header_source, friend_hle_header_destination)

    # Copy the NEX BAAS id_token cache used by Eden ACC.
    baas_cache_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_baas_token_cache.h"
    baas_cache_destination = eden / "src/core/hle/service/acc/nextendo_baas_token_cache.h"
    if not baas_cache_source.is_file():
        raise RuntimeError(f"Missing NEX BAAS token cache header: {baas_cache_source}")
    baas_cache_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(baas_cache_source, baas_cache_destination)

    # Copy the exact-host DNS redirect cache used by Eden's guest resolver.
    dns_cache_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_dns_redirect_cache.h"
    dns_cache_destination = eden / "src/core/internal_network/nextendo_dns_redirect_cache.h"
    if not dns_cache_source.is_file():
        raise RuntimeError(f"Missing NEX DNS redirect cache header: {dns_cache_source}")
    dns_cache_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(dns_cache_source, dns_cache_destination)

    # Copy the bounded UDP socket keeper used to preserve the NAT mapping
    # across the short probe-socket close/reopen sequence in P2P titles.
    udp_keeper_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_udp_port_keeper.h"
    udp_keeper_destination = eden / "src/core/internal_network/nextendo_udp_port_keeper.h"
    if not udp_keeper_source.is_file():
        raise RuntimeError(f"Missing NEX UDP port keeper header: {udp_keeper_source}")
    udp_keeper_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(udp_keeper_source, udp_keeper_destination)

    # Copy the payload-blind PRUDP SYN/CONNECT observer used only for safe diagnostics.
    prudp_observer_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_prudp_observer.h"
    prudp_observer_destination = eden / "src/core/internal_network/nextendo_prudp_observer.h"
    if not prudp_observer_source.is_file():
        raise RuntimeError(f"Missing NEX PRUDP observer header: {prudp_observer_source}")
    shutil.copy2(prudp_observer_source, prudp_observer_destination)

    # Copy the bounded station-address rewriter, activated only after a successful
    # two-vantage NNCS observation provides a public IPv4 address.
    nat_rewrite_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_nat_station_rewrite.h"
    nat_rewrite_destination = eden / "src/core/hle/service/ssl/nextendo_nat_station_rewrite.h"
    if not nat_rewrite_source.is_file():
        raise RuntimeError(f"Missing NEX NAT station rewriter: {nat_rewrite_source}")
    nat_rewrite_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(nat_rewrite_source, nat_rewrite_destination)

    native_cpp = app_dir / "src/main/jni/native.cpp"
    replace_once(
        native_cpp,
        "#include <codecvt>\n",
        "#include <algorithm>\n#include <codecvt>\n#include <string>\n",
    )
    replace_once(
        native_cpp,
        '#include "core/hle/service/filesystem/filesystem.h"\n',
        '#include "core/hle/service/filesystem/filesystem.h"\n'
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n'
        '#include "core/hle/service/acc/nextendo_baas_token_cache.h"\n'
        '#include "core/internal_network/nextendo_dns_redirect_cache.h"\n'
        '#include "core/internal_network/network.h"\n'
        '#include "core/internal_network/nextendo_prudp_observer.h"\n'
        '#include "core/hle/service/ssl/nextendo_nat_station_rewrite.h"\n',
    )
    native_jni_anchor = """JNIEXPORT jobjectArray JNICALL Java_org_yuzu_yuzu_1emu_NativeLibrary_getAllUsers(
        JNIEnv* env,
        [[maybe_unused]] jobject obj) {
"""
    native_jni_bridge = r'''JNIEXPORT jobjectArray JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_getNextendoPrudpDiagnostics(
    JNIEnv* env, [[maybe_unused]] jobject obj) {
    const auto events = Common::NextendoPrudpObserver::GetRecentEvents();
    const auto packets = Common::NextendoPrudpObserver::GetRecentPackets();
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(events.size() + packets.size()), string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (result == nullptr) {
        return nullptr;
    }

    for (jsize event_index = 0; event_index < static_cast<jsize>(events.size()); ++event_index) {
        const auto& event = events[static_cast<std::size_t>(event_index)];
        std::string line = event.server_name + " | " + event.stage + " " + event.status;
        if (event.stage == "TLS") {
            line += event.verification_skipped ? " verify=skipped" : " verify=enabled";
        }
        jstring value = env->NewStringUTF(line.c_str());
        if (value == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(result, event_index, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }

    for (jsize packet_index = 0; packet_index < static_cast<jsize>(packets.size()); ++packet_index) {
        const auto& packet = packets[static_cast<std::size_t>(packet_index)];
        const jsize index = static_cast<jsize>(events.size()) + packet_index;
        std::string line = packet.server_name;
        line += packet.from_client ? " | OUT " : " | IN ";
        line += packet.type == 0 ? "SYN" : "CONNECT";
        line += " flags=" + std::to_string(packet.flags);
        line += " src=" + std::to_string(packet.source_type) + ":" +
                std::to_string(packet.source_port);
        line += " dst=" + std::to_string(packet.destination_type) + ":" +
                std::to_string(packet.destination_port);
        line += " packet=" + std::to_string(packet.packet_id);
        line += " minor=" + std::to_string(packet.minor_version);
        line += " functions=" + std::to_string(packet.supported_functions);
        line += " payload_bytes=" + std::to_string(packet.payload_bytes);

        jstring value = env->NewStringUTF(line.c_str());
        if (value == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }
    return result;
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_clearNextendoPrudpDiagnostics(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Common::NextendoPrudpObserver::ClearRecentPackets();
}

JNIEXPORT jboolean JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_updateNextendoDnsRedirect(
    JNIEnv* env, [[maybe_unused]] jobject obj, jstring hostname, jstring ipv4) {
    if (hostname == nullptr || ipv4 == nullptr) {
        return JNI_FALSE;
    }
    const char* hostname_chars = env->GetStringUTFChars(hostname, nullptr);
    const char* ipv4_chars = env->GetStringUTFChars(ipv4, nullptr);
    if (hostname_chars == nullptr || ipv4_chars == nullptr) {
        if (hostname_chars != nullptr) env->ReleaseStringUTFChars(hostname, hostname_chars);
        if (ipv4_chars != nullptr) env->ReleaseStringUTFChars(ipv4, ipv4_chars);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    const bool accepted = Common::NextendoDnsRedirectCache::Set(hostname_chars, ipv4_chars);
    env->ReleaseStringUTFChars(hostname, hostname_chars);
    env->ReleaseStringUTFChars(ipv4, ipv4_chars);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_clearNextendoDnsRedirects(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Common::NextendoDnsRedirectCache::Clear();
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_clearNextendoObservedExternalIpv4(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Common::NextendoNatStationRewrite::ClearObservedExternalIPv4();
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_updateNextendoFriendCache(
    JNIEnv* env, [[maybe_unused]] jobject obj, jlongArray pids, jlongArray nsas,
    jlongArray app_ids, jobjectArray usernames, jintArray statuses, jobjectArray app_fields) {
    if (pids == nullptr || nsas == nullptr || app_ids == nullptr ||
        usernames == nullptr || statuses == nullptr || app_fields == nullptr) {
        Common::NextendoFriendCache::Clear();
        return;
    }

    const jsize pid_count = env->GetArrayLength(pids);
    const jsize nsa_count = env->GetArrayLength(nsas);
    const jsize app_id_count = env->GetArrayLength(app_ids);
    const jsize name_count = env->GetArrayLength(usernames);
    const jsize status_count = env->GetArrayLength(statuses);
    const jsize field_count = env->GetArrayLength(app_fields);
    const jsize count = std::min<jsize>(
        std::min(std::min(std::min(pid_count, nsa_count), app_id_count), std::min(name_count, status_count)),
        std::min(field_count, static_cast<jsize>(Common::NextendoFriendCache::MaxFriends)));

    std::vector<jlong> pid_values(static_cast<std::size_t>(count));
    std::vector<jlong> nsa_values(static_cast<std::size_t>(count));
    std::vector<jlong> app_id_values(static_cast<std::size_t>(count));
    std::vector<jint> status_values(static_cast<std::size_t>(count));
    if (count > 0) {
        env->GetLongArrayRegion(pids, 0, count, pid_values.data());
        env->GetLongArrayRegion(nsas, 0, count, nsa_values.data());
        env->GetLongArrayRegion(app_ids, 0, count, app_id_values.data());
        env->GetIntArrayRegion(statuses, 0, count, status_values.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            Common::NextendoFriendCache::Clear();
            return;
        }
    }

    std::vector<Common::NextendoFriendCache::Entry> entries;
    entries.reserve(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto name_object = static_cast<jstring>(env->GetObjectArrayElement(usernames, i));
        auto field_object = static_cast<jbyteArray>(env->GetObjectArrayElement(app_fields, i));
        if (name_object == nullptr) {
            if (field_object != nullptr) env->DeleteLocalRef(field_object);
            continue;
        }

        const char* name_chars = env->GetStringUTFChars(name_object, nullptr);
        if (name_chars != nullptr && status_values[static_cast<std::size_t>(i)] >= 0 &&
            status_values[static_cast<std::size_t>(i)] <= 2) {
            Common::NextendoFriendCache::Entry entry{
                .pid = static_cast<std::uint64_t>(pid_values[static_cast<std::size_t>(i)]),
                .network_service_account_id =
                    static_cast<std::uint64_t>(nsa_values[static_cast<std::size_t>(i)]),
                .username = name_chars,
                .presence_status = static_cast<std::uint32_t>(status_values[static_cast<std::size_t>(i)]),
                .application_id =
                    static_cast<std::uint64_t>(app_id_values[static_cast<std::size_t>(i)]),
            };

            if (field_object != nullptr) {
                const jsize field_length = env->GetArrayLength(field_object);
                if (field_length >= 0 &&
                    field_length <= static_cast<jsize>(Common::NextendoFriendCache::MaxAppFieldBytes)) {
                    entry.app_field_size = static_cast<std::size_t>(field_length);
                    if (field_length > 0) {
                        env->GetByteArrayRegion(
                            field_object, 0, field_length,
                            reinterpret_cast<jbyte*>(entry.app_field.data()));
                    }
                }
            }

            if (!env->ExceptionCheck()) {
                entries.push_back(std::move(entry));
            } else {
                env->ExceptionClear();
            }
            env->ReleaseStringUTFChars(name_object, name_chars);
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }

        if (field_object != nullptr) env->DeleteLocalRef(field_object);
        env->DeleteLocalRef(name_object);
    }

    Common::NextendoFriendCache::Replace(std::move(entries));
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_clearNextendoFriendCache(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Common::NextendoFriendCache::Clear();
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_updateNextendoIdentity(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj, jlong pid) {
    const auto current_pid = Common::NextendoFriendCache::GetPlayerPid();
    if (pid > 0) {
        if (current_pid != static_cast<std::uint64_t>(pid)) {
            Network::ClearNextendoUdpSocketCache();
            Common::NextendoNatStationRewrite::ClearObservedExternalIPv4();
        }
        Common::NextendoFriendCache::SetPlayerPid(static_cast<std::uint64_t>(pid));
    } else {
        Network::ClearNextendoUdpSocketCache();
        Common::NextendoFriendCache::ClearPlayerPid();
    }
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_clearNextendoIdentity(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Network::ClearNextendoUdpSocketCache();
    Common::NextendoFriendCache::ClearPlayerPid();
    Common::NextendoNatStationRewrite::ClearObservedExternalIPv4();
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_updateNextendoBaasIdToken(
    JNIEnv* env, [[maybe_unused]] jobject obj, jbyteArray token) {
    constexpr jsize MaxTokenBytes = 8192;
    if (token == nullptr) {
        Common::NextendoBaasTokenCache::Clear();
        return;
    }
    const jsize length = env->GetArrayLength(token);
    if (length <= 0 || length > MaxTokenBytes) {
        Common::NextendoBaasTokenCache::Clear();
        return;
    }
    std::vector<jbyte> bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(token, 0, length, bytes.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        Common::NextendoBaasTokenCache::Clear();
        return;
    }
    Common::NextendoBaasTokenCache::Set(
        reinterpret_cast<const std::uint8_t*>(bytes.data()),
        static_cast<std::size_t>(bytes.size()));
    std::fill(bytes.begin(), bytes.end(), 0);
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_clearNextendoBaasIdToken(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Common::NextendoBaasTokenCache::Clear();
}

'''
    replace_once(native_cpp, native_jni_anchor, native_jni_bridge + native_jni_anchor)

    # Expose the provisioned NEX BAAS id_token through Eden's application ACC service.
    acc_cpp = eden / "src/core/hle/service/acc/acc.cpp"
    replace_once(
        acc_cpp,
        '#include "core/hle/service/acc/acc.h"\n',
        '#include "core/hle/service/acc/acc.h"\n'
        '#include "core/hle/service/acc/nextendo_baas_token_cache.h"\n'
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n',
    )
    replace_once(
        acc_cpp,
        """    void LoadIdTokenCache(HLERequestContext& ctx) {
        LOG_WARNING(Service_ACC, "(STUBBED) called");

        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(0);
    }""",
        """    void LoadIdTokenCache(HLERequestContext& ctx) {
        auto token_data = Common::NextendoBaasTokenCache::Get();
        const auto capacity = ctx.GetWriteBufferSize();
        if (token_data.empty() || token_data.size() > capacity) {
            LOG_WARNING(Service_ACC,
                        "[NEX] BAAS id_token unavailable or output buffer too small (token={}, capacity={})",
                        token_data.size(), capacity);
            std::fill(token_data.begin(), token_data.end(), 0);
            IPC::ResponseBuilder rb{ctx, 3};
            rb.Push(ResultSuccess);
            rb.Push<u32>(0);
            return;
        }

        ctx.WriteBuffer(token_data);
        const auto token_size = static_cast<u32>(token_data.size());
        std::fill(token_data.begin(), token_data.end(), 0);
        LOG_INFO(Service_ACC, "[NEX] LoadIdTokenCache returned {} bytes", token_size);
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(token_size);
    }""",
    )
    replace_once(
        acc_cpp,
        """    void CheckAvailability(HLERequestContext& ctx) {
        LOG_DEBUG(Service_ACC, "(STUBBED) called");
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(false); // TODO: Check when this is supposed to return true and when not
    }""",
        """    void CheckAvailability(HLERequestContext& ctx) {
        const bool available = Common::NextendoBaasTokenCache::HasToken();
        LOG_DEBUG(Service_ACC, "[NEX] CheckAvailability -> {}", available);
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(available);
    }""",
    )
    replace_once(
        acc_cpp,
        """    void LoadIdTokenCache(HLERequestContext& ctx) {
        LOG_WARNING(Service_ACC, "(STUBBED) called");

        std::vector<u8> token_data(0x100);
        std::fill(token_data.begin(), token_data.end(), u8(0));

        ctx.WriteBuffer(token_data);

        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(static_cast<u32>(token_data.size()));
    }""",
        """    void LoadIdTokenCache(HLERequestContext& ctx) {
        auto token_data = Common::NextendoBaasTokenCache::Get();
        const auto capacity = ctx.GetWriteBufferSize();
        if (token_data.empty() || token_data.size() > capacity) {
            LOG_WARNING(Service_ACC,
                        "[NEX] BAAS id_token unavailable or output buffer too small (token={}, capacity={})",
                        token_data.size(), capacity);
            std::fill(token_data.begin(), token_data.end(), 0);
            IPC::ResponseBuilder rb{ctx, 3};
            rb.Push(ResultSuccess);
            rb.Push<u32>(0);
            return;
        }

        ctx.WriteBuffer(token_data);
        const auto token_size = static_cast<u32>(token_data.size());
        std::fill(token_data.begin(), token_data.end(), 0);
        LOG_INFO(Service_ACC, "[NEX] LoadIdTokenCache returned {} bytes", token_size);
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(token_size);
    }""",
    )
    # Nextendo's reference emulator binds the persistent Nextendo PID to the
    # Switch NetworkServiceAccountId. Keep Eden's local UUID hash as the offline fallback.
    replace_once(
        acc_cpp,
        """    void GetAccountId(HLERequestContext& ctx) {
        LOG_DEBUG(Service_ACC, "called");

        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.PushRaw<u64>(profile_manager->GetLastOpenedUser().Hash());
    }""",
        """    void GetAccountId(HLERequestContext& ctx) {
        const auto nextendo_pid = Common::NextendoFriendCache::GetPlayerPid();
        const auto account_id = nextendo_pid != 0
            ? nextendo_pid
            : profile_manager->GetLastOpenedUser().Hash();
        LOG_DEBUG(Service_ACC, "[NEX] GetAccountId -> {}", account_id);

        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.PushRaw<u64>(account_id);
    }""",
    )
    replace_once(
        acc_cpp,
        """        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.PushRaw<u64>(profile_manager->GetLastOpenedUser().Hash());
    }

    void StoreOpenContext(HLERequestContext& ctx) {""",
        """        const auto nextendo_pid = Common::NextendoFriendCache::GetPlayerPid();
        const auto account_id = nextendo_pid != 0
            ? nextendo_pid
            : profile_manager->GetLastOpenedUser().Hash();
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.PushRaw<u64>(account_id);
    }

    void StoreOpenContext(HLERequestContext& ctx) {""",
    )

    # Rewrite only recognized outbound Nextendo matchmaking station strings.
    # The owned buffer is retained across ResultWouldBlock retries as required by OpenSSL.
    ssl_cpp = eden / "src/core/hle/service/ssl/ssl.cpp"
    replace_once(
        ssl_cpp,
        '#include "core/hle/service/ssl/ssl.h"\n',
        '#include "core/hle/service/ssl/ssl.h"\n'
        '#include "core/hle/service/ssl/nextendo_nat_station_rewrite.h"\n',
    )
    replace_once(
        ssl_cpp,
        "    bool did_handshake = false;\n",
        "    bool did_handshake = false;\n"
        "    std::vector<u8> nextendo_pending_original_write;\n"
        "    std::vector<u8> nextendo_pending_rewritten_write;\n",
    )
    replace_once(
        ssl_cpp,
        """    Result WriteImpl(size_t* out_size, std::span<const u8> data) {
        ASSERT_OR_EXECUTE(did_handshake, { return ResultInternalError; });
        return backend->Write(out_size, data);
    }""",
        """    Result WriteImpl(size_t* out_size, std::span<const u8> data) {
        ASSERT_OR_EXECUTE(did_handshake, { return ResultInternalError; });

        if (!nextendo_pending_original_write.empty()) {
            const bool same_pending_write =
                data.size() == nextendo_pending_original_write.size() &&
                std::equal(data.begin(), data.end(), nextendo_pending_original_write.begin());
            if (!same_pending_write) {
                LOG_WARNING(Service_SSL,
                            "[NEX] Deferring another SSL write while a rewritten frame is pending");
                return ResultWouldBlock;
            }
        } else if (!data.empty()) {
            nextendo_pending_original_write.assign(data.begin(), data.end());
            nextendo_pending_rewritten_write.clear();
            std::size_t rewritten_count = 0;
            if (Common::NextendoNatStationRewrite::TryFixupStationAddress(
                    data, nextendo_pending_rewritten_write, &rewritten_count)) {
                LOG_INFO(Service_SSL,
                         "[NEX] Rewrote {} private station address(es) in matchmaking traffic",
                         rewritten_count);
            } else {
                nextendo_pending_original_write.clear();
            }
        }

        const bool station_address_rewritten = !nextendo_pending_rewritten_write.empty();
        const std::span<const u8> send_data =
            station_address_rewritten
                ? std::span<const u8>(nextendo_pending_rewritten_write)
                : data;
        const Result result = backend->Write(out_size, send_data);
        if (result == ResultSuccess && station_address_rewritten) {
            // SSL Write reports bytes consumed from the guest buffer. The wire
            // frame may have grown/shrunk when the IPv4 text length changed.
            if (*out_size != send_data.size()) {
                LOG_ERROR(Service_SSL,
                          "[NEX] Rewritten SSL write completed partially; refusing to desync guest stream");
                nextendo_pending_original_write.clear();
                nextendo_pending_rewritten_write.clear();
                return ResultInternalError;
            }
            *out_size = nextendo_pending_original_write.size();
        }
        if (result != ResultWouldBlock) {
            nextendo_pending_original_write.clear();
            nextendo_pending_rewritten_write.clear();
        }
        return result;
    }""",
    )

    # Route only explicitly provisioned Nintendo service hostnames. The original
    # hostname remains in the guest resolver result so TLS/SNI can select the correct
    # Nextendo virtual host; there is no wildcard or all-Nintendo redirect.
    sfdnsres_cpp = eden / "src/core/hle/service/sockets/sfdnsres.cpp"
    replace_once(
        sfdnsres_cpp,
        '#include "core/hle/service/sockets/sfdnsres.h"\n',
        '#include "core/hle/service/sockets/sfdnsres.h"\n'
        '#include "core/internal_network/nextendo_dns_redirect_cache.h"\n',
    )
    sfdnsres_text = sfdnsres_cpp.read_text(encoding="utf-8")
    block_guard = "if (IsBlockedHost(host))"
    if sfdnsres_text.count(block_guard) != 2:
        raise RuntimeError("Could not identify both SFDNSRES Nintendo-domain safety guards")
    sfdnsres_text = sfdnsres_text.replace(
        block_guard,
        "if (IsBlockedHost(host) && !Common::NextendoDnsRedirectCache::Resolve(host).has_value())",
    )
    helper_anchor = """static NetDbError GetAddrInfoErrorToNetDbError(GetAddrInfoError result) {"""
    helper = """static auto GetNextendoAwareAddressInfo(
    const std::string& host, const std::optional<std::string>& service) {
    const auto redirected = Common::NextendoDnsRedirectCache::Resolve(host);
    const std::string& lookup_host = redirected.has_value() ? *redirected : host;
    auto result = Network::GetAddressInfo(lookup_host, service);
    if (redirected.has_value() && result.has_value()) {
        for (auto& entry : result.value()) {
            entry.canon_name = host;
        }
        LOG_INFO(Network, "[NEX] Redirected exact host '{}' to {} (original hostname retained)",
                 host, *redirected);
    }
    return result;
}

"""
    if sfdnsres_text.count(helper_anchor) != 1:
        raise RuntimeError("Could not identify SFDNSRES resolver helper insertion point")
    sfdnsres_text = sfdnsres_text.replace(helper_anchor, helper + helper_anchor, 1)
    if sfdnsres_text.count("Network::GetAddressInfo(host, /*service*/ std::nullopt)") != 1:
        raise RuntimeError("Could not identify GetHostByName resolver call")
    sfdnsres_text = sfdnsres_text.replace(
        "Network::GetAddressInfo(host, /*service*/ std::nullopt)",
        "GetNextendoAwareAddressInfo(host, /*service*/ std::nullopt)",
        1,
    )
    if sfdnsres_text.count("Network::GetAddressInfo(host, service)") != 1:
        raise RuntimeError("Could not identify GetAddrInfo resolver call")
    sfdnsres_text = sfdnsres_text.replace(
        "Network::GetAddressInfo(host, service)",
        "GetNextendoAwareAddressInfo(host, service)",
        1,
    )
    sfdnsres_cpp.write_text(sfdnsres_text, encoding="utf-8")

    # Capture NNCS replies from the guest game's real UDP socket. A separate
    # app-owned DatagramSocket changes the NAT mapping and is not authoritative.
    bsd_cpp = eden / "src/core/hle/service/sockets/bsd.cpp"
    replace_once(
        bsd_cpp,
        '#include "core/hle/service/sockets/bsd.h"\n',
        '#include "core/hle/service/sockets/bsd.h"\n'
        '#include "core/hle/service/ssl/nextendo_nat_station_rewrite.h"\n',
    )
    replace_once(
        bsd_cpp,
        """    const auto [ret, bsd_errno] = Translate(descriptor.socket->RecvFrom(flags, message, p_addr_in));

        // Restore original state""",
        """    const auto [ret, bsd_errno] = Translate(descriptor.socket->RecvFrom(flags, message, p_addr_in));

        if (ret == 16 && bsd_errno == Errno::SUCCESS && p_addr_in != nullptr &&
            Common::NextendoNatStationRewrite::ObserveNncsReply(
                addr_in.portno,
                std::span<const u8>(message.data(), static_cast<std::size_t>(ret)))) {
            LOG_INFO(Service,
                     "[NEX] Captured the public NAT mapping from both NNCS vantages "
                     "on the game's UDP socket");
        }

        // Restore original state""",
    )

    network_header = eden / "src/core/internal_network/network.h"
    replace_once(
        network_header,
        "void RestartSocketOperations();\n",
        "void RestartSocketOperations();\n"
        "\n"
        "/** Clears short-lived Nextendo UDP sockets when the linked account changes. */\n"
        "void ClearNextendoUdpSocketCache();\n",
    )

    # Keep a short-lived host UDP socket alive across the game's NAT-probe
    # close/reopen sequence. This preserves the external mapping announced to peers.
    network_cpp = eden / "src/core/internal_network/network.cpp"
    replace_once(
        network_cpp,
        '#include "core/internal_network/sockets.h"\n',
        '#include "core/internal_network/sockets.h"\n'
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n'
        '#include "core/internal_network/nextendo_udp_port_keeper.h"\n',
    )
    udp_keeper_helpers = r'''using NextendoUdpSocketCache = Common::NextendoUdpPortKeeper::Cache<SOCKET>;

NextendoUdpSocketCache& GetNextendoUdpSocketCache() {
    static NextendoUdpSocketCache cache;
    return cache;
}

void CloseNextendoUdpSockets(const std::vector<SOCKET>& sockets) {
    for (const SOCKET socket : sockets) {
        if (socket != static_cast<SOCKET>(-1)) {
            (void)closesocket(socket);
        }
    }
}

bool IsNextendoUnconnectedUdpSocket(SOCKET socket, sockaddr_in* local_address) {
    if (socket == static_cast<SOCKET>(-1)) {
        return false;
    }

    int socket_type = 0;
    socklen_t socket_type_size = sizeof(socket_type);
    if (getsockopt(socket, SOL_SOCKET, SO_TYPE,
                   reinterpret_cast<char*>(&socket_type), &socket_type_size) != 0 ||
        socket_type != SOCK_DGRAM) {
        return false;
    }

    sockaddr_in local{};
    socklen_t local_size = sizeof(local);
    if (getsockname(socket, reinterpret_cast<sockaddr*>(&local), &local_size) != 0 ||
        local.sin_family != AF_INET || local.sin_port == 0) {
        return false;
    }

    sockaddr_in peer{};
    socklen_t peer_size = sizeof(peer);
    if (getpeername(socket, reinterpret_cast<sockaddr*>(&peer), &peer_size) == 0) {
        return false;
    }

    if (local_address != nullptr) {
        *local_address = local;
    }
    return true;
}

bool TryParkNextendoUdpSocket(SOCKET socket, bool non_blocking) {
    if (Common::NextendoFriendCache::GetPlayerPid() == 0) {
        return false;
    }

    sockaddr_in local{};
    if (!IsNextendoUnconnectedUdpSocket(socket, &local)) {
        return false;
    }

    const auto address = TranslateIPv4(local.sin_addr);
    const auto close_handles = GetNextendoUdpSocketCache().Park(
        ntohs(local.sin_port), address, socket, non_blocking);
    CloseNextendoUdpSockets(close_handles);
    LOG_INFO(Network, "[NEX] Parked UDP socket on local port {} for P2P NAT mapping reuse",
             ntohs(local.sin_port));
    return true;
}

bool TryAdoptNextendoUdpSocket(SOCKET& socket, const SockAddrIn& bind_address,
                               bool& non_blocking) {
    if (Common::NextendoFriendCache::GetPlayerPid() == 0 || socket == static_cast<SOCKET>(-1) ||
        bind_address.portno == 0) {
        return false;
    }

    int socket_type = 0;
    socklen_t socket_type_size = sizeof(socket_type);
    if (getsockopt(socket, SOL_SOCKET, SO_TYPE,
                   reinterpret_cast<char*>(&socket_type), &socket_type_size) != 0 ||
        socket_type != SOCK_DGRAM) {
        return false;
    }

    sockaddr_in local{};
    socklen_t local_size = sizeof(local);
    if (getsockname(socket, reinterpret_cast<sockaddr*>(&local), &local_size) != 0 ||
        local.sin_port != 0) {
        return false;
    }

    auto result = GetNextendoUdpSocketCache().Take(bind_address.portno, bind_address.ip);
    CloseNextendoUdpSockets(result.close_handles);
    if (!result.adopted.has_value()) {
        return false;
    }

    (void)closesocket(socket);
    socket = result.adopted->handle;
    non_blocking = result.adopted->non_blocking;
    LOG_INFO(Network, "[NEX] Reused parked UDP socket on local port {} for P2P",
             bind_address.portno);
    return true;
}

void ClearParkedNextendoUdpSockets() {
    CloseNextendoUdpSockets(GetNextendoUdpSocketCache().Clear());
}

'''
    network_anchor = "Errno GetAndLogLastError(CallType call_type = CallType::Other) {\n"
    replace_once(network_cpp, network_anchor, udp_keeper_helpers + network_anchor)

    replace_once(
        network_cpp,
        """NetworkInstance::~NetworkInstance() {
    Finalize();
}""",
        """NetworkInstance::~NetworkInstance() {
    ClearParkedNextendoUdpSockets();
    Finalize();
}""",
    )
    replace_once(
        network_cpp,
        "NetworkInstance::NetworkInstance() {\n",
        "void ClearNextendoUdpSocketCache() {\n"
        "    ClearParkedNextendoUdpSockets();\n"
        "}\n\n"
        "NetworkInstance::NetworkInstance() {\n",
    )
    replace_once(
        network_cpp,
        """Errno Socket::Bind(SockAddrIn addr) {
    const sockaddr addr_in = TranslateFromSockAddrIn(addr);
""",
        """Errno Socket::Bind(SockAddrIn addr) {
    if (TryAdoptNextendoUdpSocket(fd, addr, is_non_blocking)) {
        return Errno::SUCCESS;
    }

    const sockaddr addr_in = TranslateFromSockAddrIn(addr);
""",
    )
    replace_once(
        network_cpp,
        """Errno Socket::Close() {
    [[maybe_unused]] const int result = closesocket(fd);
""",
        """Errno Socket::Close() {
    if (TryParkNextendoUdpSocket(fd, is_non_blocking)) {
        fd = INVALID_SOCKET;
        return Errno::SUCCESS;
    }

    [[maybe_unused]] const int result = closesocket(fd);
""",
    )


    # Observe only SMM2/Nextendo WSS handshakes after TLS decryption. The observer
    # emits virtual station metadata for SYN/CONNECT only; it never logs DATA/RMC
    # payloads, tickets, signatures, or BAAS tokens.
    ssl_backend = eden / "src/core/hle/service/ssl/ssl_backend_openssl.cpp"
    replace_once(
        ssl_backend,
        '#include "core/internal_network/network.h"\n',
        '#include "core/internal_network/network.h"\n'
        '#include "core/internal_network/nextendo_prudp_observer.h"\n',
    )
    replace_once(
        ssl_backend,
        """        if (!SSL_set_tlsext_host_name(ssl, hostname.c_str())) { // hostname for SNI
            LOG_ERROR(Service_SSL, "SSL_set_tlsext_host_name({}) failed", hostname);
            return CheckOpenSSLErrors();
        }
        return ResultSuccess;
""",
        """        if (!SSL_set_tlsext_host_name(ssl, hostname.c_str())) { // hostname for SNI
            LOG_ERROR(Service_SSL, "SSL_set_tlsext_host_name({}) failed", hostname);
            return CheckOpenSSLErrors();
        }
        nextendo_prudp_observer.SetServerName(hostname);
        return ResultSuccess;
""",
    )
    replace_once(
        ssl_backend,
        """    Result DoHandshake() override {
        SSL_set_verify_result(ssl, X509_V_OK);
        const int ret = SSL_do_handshake(ssl);

        if (!skip_cert_verification) {
            const long verify_result = SSL_get_verify_result(ssl);
            if (verify_result != X509_V_OK) {
                LOG_ERROR(Service_SSL, "SSL cert verification failed because: {}",
                          X509_verify_cert_error_string(verify_result));
                return CheckOpenSSLErrors();
            }
        }

        if (ret <= 0) {
            const int ssl_err = SSL_get_error(ssl, ret);
            if (ssl_err == SSL_ERROR_ZERO_RETURN ||
                (ssl_err == SSL_ERROR_SYSCALL && got_read_eof)) {
                LOG_ERROR(Service_SSL, "SSL handshake failed because server hung up");
                return ResultInternalError;
            }
        }
        return HandleReturn("SSL_do_handshake", 0, ret);
    }
""",
        """    Result DoHandshake() override {
        const auto record_handshake = [this](Result result) {
            nextendo_prudp_observer.RecordTlsHandshake(
                result == ResultSuccess, result == ResultWouldBlock, skip_cert_verification);
            return result;
        };

        SSL_set_verify_result(ssl, X509_V_OK);
        const int ret = SSL_do_handshake(ssl);

        if (!skip_cert_verification) {
            const long verify_result = SSL_get_verify_result(ssl);
            if (verify_result != X509_V_OK) {
                LOG_ERROR(Service_SSL, "SSL cert verification failed because: {}",
                          X509_verify_cert_error_string(verify_result));
                return record_handshake(CheckOpenSSLErrors());
            }
        }

        if (ret <= 0) {
            const int ssl_err = SSL_get_error(ssl, ret);
            if (ssl_err == SSL_ERROR_ZERO_RETURN ||
                (ssl_err == SSL_ERROR_SYSCALL && got_read_eof)) {
                LOG_ERROR(Service_SSL, "SSL handshake failed because server hung up");
                return record_handshake(ResultInternalError);
            }
        }
        return record_handshake(HandleReturn("SSL_do_handshake", 0, ret));
    }
""",
    )
    replace_once(
        ssl_backend,
        """    Result Read(size_t* out_size, std::span<u8> data) override {
        const int ret = SSL_read_ex(ssl, data.data(), data.size(), out_size);
        return HandleReturn("SSL_read_ex", out_size, ret);
    }

    Result Write(size_t* out_size, std::span<const u8> data) override {
        const int ret = SSL_write_ex(ssl, data.data(), data.size(), out_size);
        return HandleReturn("SSL_write_ex", out_size, ret);
    }
""",
        """    Result Read(size_t* out_size, std::span<u8> data) override {
        const int ret = SSL_read_ex(ssl, data.data(), data.size(), out_size);
        const Result result = HandleReturn("SSL_read_ex", out_size, ret);
        if (result == ResultSuccess && out_size != nullptr && *out_size > 0) {
            for (const auto& packet :
                 nextendo_prudp_observer.ObserveInbound(data.data(), *out_size)) {
                LOG_INFO(Service_SSL,
                         "[NEX PRUDP DIAG] dir=in sni={} type={} flags=0x{:x} src={}:{} dst={}:{} packet={} minor={} supported=0x{:06x} payload_bytes={}",
                         packet.server_name, packet.type, packet.flags,
                         packet.source_type, packet.source_port,
                         packet.destination_type, packet.destination_port,
                         packet.packet_id, packet.minor_version,
                         packet.supported_functions, packet.payload_bytes);
            }
        }
        return result;
    }

    Result Write(size_t* out_size, std::span<const u8> data) override {
        const int ret = SSL_write_ex(ssl, data.data(), data.size(), out_size);
        const Result result = HandleReturn("SSL_write_ex", out_size, ret);
        if (result == ResultSuccess && out_size != nullptr && *out_size > 0) {
            for (const auto& packet :
                 nextendo_prudp_observer.ObserveOutbound(data.data(), *out_size)) {
                LOG_INFO(Service_SSL,
                         "[NEX PRUDP DIAG] dir=out sni={} type={} flags=0x{:x} src={}:{} dst={}:{} packet={} minor={} supported=0x{:06x} payload_bytes={}",
                         packet.server_name, packet.type, packet.flags,
                         packet.source_type, packet.source_port,
                         packet.destination_type, packet.destination_port,
                         packet.packet_id, packet.minor_version,
                         packet.supported_functions, packet.payload_bytes);
            }
        }
        return result;
    }
""",
    )
    replace_once(
        ssl_backend,
        """    bool skip_cert_verification = false;

    std::shared_ptr<Network::SocketBase> socket;
""",
        """    bool skip_cert_verification = false;
    Common::NextendoPrudpObserver nextendo_prudp_observer;

    std::shared_ptr<Network::SocketBase> socket;
""",
    )

    # Connect Eden's nn::friends HLE to the NEX-owned read-only cache.
    friend_cpp = eden / "src/core/hle/service/friend/friend.cpp"
    replace_once(
        friend_cpp,
        "#include <queue>\n",
        "#include <algorithm>\n"
        "#include <array>\n"
        "#include <cstddef>\n"
        "#include <cstring>\n"
        "#include <queue>\n"
        "#include <vector>\n"
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n'
        '#include "core/hle/service/friend/nextendo_friend_hle.h"\n',
    )
    friend_support = r'''// NEX-owned friend record serializers. Unknown identity fields remain zero.
namespace {
using namespace Common::NextendoFriendHle;

std::vector<u64> ReadNextendoPids(HLERequestContext& ctx) {
    constexpr std::size_t MaxRequestedIds = Common::NextendoFriendCache::MaxFriends;
    std::vector<u64> pids;
    if (!ctx.CanReadBuffer(0)) {
        return pids;
    }
    const auto raw = ctx.ReadBuffer(0);
    const auto count = std::min(raw.size() / sizeof(u64), MaxRequestedIds);
    pids.resize(count);
    if (count > 0) {
        std::memcpy(pids.data(), raw.data(), count * sizeof(u64));
    }
    return pids;
}

void RespondNextendoProfileList(HLERequestContext& ctx, const char* label) {
    const auto requested = ReadNextendoPids(ctx);
    const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
    const auto capacity = std::min(
        ctx.GetWriteBufferNumElements<NextendoProfileInfo>(),
        Common::NextendoFriendCache::MaxFriends);

    std::vector<NextendoProfileInfo> profiles;
    profiles.reserve(std::min(requested.size(), capacity));
    for (const auto pid : requested) {
        if (profiles.size() >= capacity) {
            break;
        }
        const auto found = std::find_if(friends.begin(), friends.end(),
            [pid](const auto& entry) {
                return entry.network_service_account_id == pid;
            });
        if (found == friends.end()) {
            continue;
        }

        NextendoProfileInfo profile{};
        profile.network_user_id = pid;
        const auto length = std::min(found->username.size(), profile.nickname.size() - 1);
        std::memcpy(profile.nickname.data(), found->username.data(), length);
        profiles.push_back(profile);
    }

    if (!profiles.empty()) {
        ctx.WriteBuffer(profiles);
    }
    LOG_INFO(Service_Friend, "[NEX Nextendo] {} requested={} resolved={}",
             label, requested.size(), profiles.size());
    IPC::ResponseBuilder rb{ctx, 3};
    rb.Push(ResultSuccess);
    rb.Push<u32>(static_cast<u32>(profiles.size()));
}

void RespondNextendoFriendDetails(HLERequestContext& ctx, const char* label) {
    const auto requested = ReadNextendoPids(ctx);
    const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
    const auto capacity = std::min(
        ctx.GetWriteBufferNumElements<NextendoFriendInfo>(),
        Common::NextendoFriendCache::MaxFriends);
    const auto count = std::min(requested.size(), capacity);

    std::vector<NextendoFriendInfo> details;
    details.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        const auto requested_network_id = requested[i];
        const auto found = std::find_if(friends.begin(), friends.end(),
            [requested_network_id](const auto& entry) {
                return entry.network_service_account_id == requested_network_id;
            });
        details.push_back(found == friends.end() ? NextendoFriendInfo{}
                                                 : MakeNextendoFriendInfo(*found, requested_network_id));
    }
    if (!details.empty()) {
        ctx.WriteBuffer(details);
    }
    LOG_INFO(Service_Friend, "[NEX Nextendo] {} requested={} returned={}",
             label, requested.size(), details.size());
    IPC::ResponseBuilder rb{ctx, 2};
    rb.Push(ResultSuccess);
}
} // namespace

'''
    replace_once(
        friend_cpp,
        "namespace Service::Friend {\n\n",
        "namespace Service::Friend {\n\n" + friend_support + "\n",
    )
    replace_once(
        friend_cpp,
        '{10100, nullptr, "GetFriendListIds"}',
        '{10100, &IFriendService::GetFriendListIds, "GetFriendListIds"}',
    )
    replace_once(
        friend_cpp,
        '{10102, nullptr, "UpdateFriendInfo"}',
        '{10102, &IFriendService::UpdateFriendInfo, "UpdateFriendInfo"}',
    )
    replace_once(
        friend_cpp,
        '{10500, nullptr, "GetProfileList"}',
        '{10500, &IFriendService::GetProfileList, "GetProfileList"}',
    )
    replace_once(
        friend_cpp,
        '{10501, nullptr, "GetProfileListV2"}',
        '{10501, &IFriendService::GetProfileListV2, "GetProfileListV2"}',
    )
    replace_once(
        friend_cpp,
        '{20601, &IFriendService::GetUserPresenceView, "GetUserPresenceViewV2"}',
        '{20601, &IFriendService::GetUserPresenceViewV2, "GetUserPresenceViewV2"}',
    )
    replace_once(
        friend_cpp,
        '{20102, nullptr, "GetFriendDetailedInfo"}',
        '{20102, &IFriendService::GetFriendDetailedInfo, "GetFriendDetailedInfo"},\n'
        '            {20107, &IFriendService::GetFriendDetailedInfoV2, "GetFriendDetailedInfoV2"},\n'
        '            {20108, &IFriendService::GetFriendListForViewerV2, "GetFriendListForViewerV2"},\n'
        '            {20109, nullptr, "UpdateFriendInfoForViewerV2"},\n'
        '            {20111, nullptr, "LoadFriendSettingV2"}',
    )
    replace_once(
        friend_cpp,
        """    void GetUserPresenceView(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        const auto uuid = rp.PopRaw<Common::UUID>();
        LOG_DEBUG(Service_Friend, "(STUBBED) called, uuid={}.", uuid.RawString());

        u8 buf[0xe0]{};
        ctx.WriteBuffer(buf);

        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
    }""",
        """    void GetUserPresenceView(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        [[maybe_unused]] const auto uuid = rp.PopRaw<Common::UUID>();
        // The public Nextendo friend payload has no Switch account UUID mapping.
        NextendoUserPresence presence{};
        ctx.WriteBuffer(presence);
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
    }""",
    )

    get_friend_list_stub = """    void GetFriendList(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        const auto friend_offset = rp.Pop<u32>();
        const auto uuid = rp.PopRaw<Common::UUID>();
        [[maybe_unused]] const auto filter = rp.PopRaw<SizedFriendFilter>();
        const auto pid = rp.Pop<u64>();

        const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
        const auto capacity = std::min(
            ctx.GetWriteBufferNumElements<NextendoFriendInfo>(),
            Common::NextendoFriendCache::MaxFriends);
        std::vector<NextendoFriendInfo> output;
        if (friend_offset == 0) {
            output.reserve(std::min(friends.size(), capacity));
            for (const auto& entry : friends) {
                if (output.size() >= capacity) {
                    break;
                }
                output.push_back(MakeNextendoFriendInfo(entry));
            }
        }
        if (!output.empty()) {
            ctx.WriteBuffer(output);
        }

        LOG_INFO(Service_Friend, "[NEX Nextendo] GetFriendList offset={} uuid=0x{} pid={} count={}",
                 friend_offset, uuid.RawString(), pid, output.size());
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push<u32>(static_cast<u32>(output.size()));
    }"""
    replace_once(
        friend_cpp,
        """    void GetFriendList(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        const auto friend_offset = rp.Pop<u32>();
        const auto uuid = rp.PopRaw<Common::UUID>();
        [[maybe_unused]] const auto filter = rp.PopRaw<SizedFriendFilter>();
        const auto pid = rp.Pop<u64>();
        LOG_WARNING(Service_Friend, "(STUBBED) called, offset={}, uuid=0x{}, pid={}", friend_offset,
                    uuid.RawString(), pid);

        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);

        rb.Push<u32>(0); // Friend count
        // TODO(ogniK): Return a buffer of u64s which are the "NetworkServiceAccountId"
    }""",
        get_friend_list_stub,
    )

    new_handlers = """    void GetFriendListIds(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        const auto offset = rp.Pop<s32>();
        const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
        const auto capacity = ctx.GetWriteBufferNumElements<u64>();
        std::vector<u64> pids;
        if (offset == 0) {
            pids.reserve(std::min(friends.size(), capacity));
            for (const auto& entry : friends) {
                if (pids.size() >= capacity) {
                    break;
                }
                pids.push_back(entry.network_service_account_id);
            }
        }
        if (!pids.empty()) {
            ctx.WriteBuffer(pids);
        }
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push<u32>(static_cast<u32>(pids.size()));
    }

    void UpdateFriendInfo(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        [[maybe_unused]] const auto uuid = rp.PopRaw<Common::UUID>();
        RespondNextendoFriendDetails(ctx, "UpdateFriendInfo");
    }

    void GetProfileList(HLERequestContext& ctx) {
        RespondNextendoProfileList(ctx, "GetProfileList");
    }

    void GetProfileListV2(HLERequestContext& ctx) {
        RespondNextendoProfileList(ctx, "GetProfileListV2");
    }

    void GetFriendDetailedInfo(HLERequestContext& ctx) {
        RespondNextendoFriendDetails(ctx, "GetFriendDetailedInfo");
    }

    // SDK 20+: qLaunch asks for the 0x800-byte detailed friend record when a tile is opened.
    void GetFriendDetailedInfoV2(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        [[maybe_unused]] const auto user_id = rp.PopRaw<Common::UUID>();
        const auto requested_id = rp.Pop<u64>();
        std::array<u8, NextendoFriendDetailedInfoV2Size> output{};
        const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
        const auto found = std::find_if(friends.begin(), friends.end(),
            [requested_id](const auto& entry) {
                return entry.network_service_account_id == requested_id;
            });
        if (found != friends.end()) {
            WriteNextendoFriendDetailedInfoV2(output, *found, requested_id);
        }
        ctx.WriteBuffer(output);
        LOG_INFO(Service_Friend, "[NEX Nextendo] GetFriendDetailedInfoV2 id={} found={}",
                 requested_id, found != friends.end());
        IPC::ResponseBuilder rb{ctx, 2};
        rb.Push(ResultSuccess);
    }

    // SDK 22+: modern HOME Menu list. Records use a 0x220-byte stride.
    void GetFriendListForViewerV2(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        [[maybe_unused]] const auto user_id = rp.PopRaw<Common::UUID>();
        const auto offset = rp.Pop<s32>();
        [[maybe_unused]] const auto filter = rp.PopRaw<SizedFriendFilter>();
        const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
        const auto output_bytes =
            ctx.GetWriteBufferNumElements<u64>() * sizeof(u64);
        const auto capacity = output_bytes / NextendoFriendRecordV2Size;
        std::size_t count = 0;
        std::vector<u8> output;
        if (offset >= 0 && static_cast<std::size_t>(offset) < friends.size()) {
            count = std::min(friends.size() - static_cast<std::size_t>(offset), capacity);
            output.resize(count * NextendoFriendRecordV2Size);
            for (std::size_t i = 0; i < count; ++i) {
                const auto& entry = friends[static_cast<std::size_t>(offset) + i];
                WriteNextendoFriendRecordV2(
                    output.data() + i * NextendoFriendRecordV2Size,
                    entry,
                    entry.network_service_account_id);
            }
        }
        if (!output.empty()) {
            ctx.WriteBuffer(output);
        }
        LOG_INFO(Service_Friend, "[NEX Nextendo] GetFriendListForViewerV2 offset={} count={}",
                 offset, count);
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push<u32>(static_cast<u32>(count));
    }

    // qLaunch SDK 19+ asks for the connected user's own 0xE8-byte presence view.
    void GetUserPresenceViewV2(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        [[maybe_unused]] const auto user_id = rp.PopRaw<Common::UUID>();
        // No verified Switch UUID or live qLaunch presence snapshot is available.
        std::array<u8, NextendoUserPresenceViewV2Size> view{};
        ctx.WriteBuffer(view);
        IPC::ResponseBuilder rb{ctx, 2};
        rb.Push(ResultSuccess);
    }

"""
    replace_once(
        friend_cpp,
        "    void CheckFriendListAvailability(HLERequestContext& ctx) {",
        new_handlers + "    void CheckFriendListAvailability(HLERequestContext& ctx) {",
    )

    replace_once(
        friend_cpp,
        """    void GetFriendCount(HLERequestContext& ctx) {
        LOG_DEBUG(Service_Friend, "(STUBBED) called");

        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(0);
    }""",
        """    void GetFriendCount(HLERequestContext& ctx) {
        const auto count = static_cast<u32>(Common::NextendoFriendCache::GetNetworkFriends().size());
        LOG_INFO(Service_Friend, "[NEX Nextendo] GetFriendCount -> {}", count);
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push(count);
    }""",
    )

    replace_once(
        friend_cpp,
        """    void GetFriendListForViewer(HLERequestContext& ctx) {
        LOG_DEBUG(Service_Friend, "(STUBBED) called");

        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push<u32>(0);
    }""",
        """    void GetFriendListForViewer(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        const auto offset = rp.Pop<s32>();
        const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
        const auto capacity = ctx.GetWriteBufferNumElements<u64>();
        std::vector<u64> pids;
        if (offset == 0) {
            pids.reserve(std::min(friends.size(), capacity));
            for (const auto& entry : friends) {
                if (pids.size() >= capacity) {
                    break;
                }
                pids.push_back(entry.network_service_account_id);
            }
        }
        if (!pids.empty()) {
            ctx.WriteBuffer(pids);
        }
        IPC::ResponseBuilder rb{ctx, 3};
        rb.Push(ResultSuccess);
        rb.Push<u32>(static_cast<u32>(pids.size()));
    }""",
    )

    gradle = app_dir / "build.gradle.kts"
    replace_once(
        gradle,
        'applicationId = "com.miHoYo.Yuanshen"',
        'applicationId = "com.nex.nx"',
    )
    replace_once(
        gradle,
        'manifestPlaceholders += mapOf("appNameBase" to "Eden Optimized")',
        'manifestPlaceholders += mapOf("appNameBase" to "NEX")',
    )
    replace_once(
        gradle,
        'resValue("string", "app_name_suffixed", "Eden Optimized")',
        'resValue("string", "app_name_suffixed", "NEX")',
    )
    replace_once(
        gradle,
        'resValue("string", "app_name", "Eden$suffix")',
        'resValue("string", "app_name", "NEX$suffix")',
    )
    replace_once(
        gradle,
        'implementation("androidx.core:core-splashscreen:1.0.1")',
        'implementation("androidx.core:core-splashscreen:1.0.1")\n'
        '    implementation("androidx.browser:browser:1.8.0")\n'
        '    testImplementation("junit:junit:4.13.2")',
    )

    logo_source = NEX_SOURCE / "assets/nex-logo.webp"
    if not logo_source.is_file():
        raise RuntimeError(f"Missing NEX logo asset: {logo_source}")
    logo_destination = app_dir / "src/main/res/drawable-nodpi/nex_logo.webp"
    logo_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(logo_source, logo_destination)

    # Use Android's adaptive-icon system, just like Eden's launcher icon. Android
    # applies the user's circular/squircle mask; the inset keeps the NEX artwork
    # inside the adaptive-icon safe area without changing the source logo.
    foreground_xml = app_dir / "src/main/res/drawable-nodpi/nex_launcher_foreground.xml"
    foreground_xml.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@drawable/nex_logo"
    android:insetLeft="18dp"
    android:insetTop="18dp"
    android:insetRight="18dp"
    android:insetBottom="18dp" />
""",
        encoding="utf-8",
    )

    values_dir = app_dir / "src/main/res/values"
    values_dir.mkdir(parents=True, exist_ok=True)
    (values_dir / "nex_launcher_colors.xml").write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="nex_launcher_background">#000000</color>
</resources>
""",
        encoding="utf-8",
    )

    adaptive_dir = app_dir / "src/main/res/mipmap-anydpi-v26"
    fallback_dir = app_dir / "src/main/res/mipmap-nodpi"
    adaptive_dir.mkdir(parents=True, exist_ok=True)
    fallback_dir.mkdir(parents=True, exist_ok=True)
    adaptive_icon = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/nex_launcher_background" />
    <foreground android:drawable="@drawable/nex_launcher_foreground" />
</adaptive-icon>
"""
    for icon_name in ("ic_launcher_nex", "ic_launcher_nex_round"):
        (adaptive_dir / f"{icon_name}.xml").write_text(adaptive_icon, encoding="utf-8")
        # Pre-26 fallback; Android 11 and newer use the adaptive XML above.
        shutil.copy2(logo_source, fallback_dir / f"{icon_name}.webp")

    manifest = app_dir / "src/main/AndroidManifest.xml"
    callback_activity = """        <activity
            android:name="com.nex.nx.nextendo.NextendoCallbackActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:launchMode="singleTop"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data
                    android:scheme="com.nex.nx"
                    android:host="oauth"
                    android:path="/callback" />
            </intent-filter>
        </activity>

"""
    replace_once(
        manifest,
        '        <activity\n            android:name="org.yuzu.yuzu_emu.ui.main.MainActivity"',
        callback_activity
        + '        <activity\n            android:name="org.yuzu.yuzu_emu.ui.main.MainActivity"',
    )

    replace_once(
        manifest,
        'android:icon="@mipmap/ic_launcher"',
        'android:icon="@mipmap/ic_launcher_nex"',
    )
    replace_once(
        manifest,
        'android:roundIcon="@mipmap/ic_launcher_round"',
        'android:roundIcon="@mipmap/ic_launcher_nex_round"',
    )

    presenter = (
        app_dir
        / "src/main/java/org/yuzu/yuzu_emu/features/settings/ui/SettingsFragmentPresenter.kt"
    )
    text = presenter.read_text(encoding="utf-8")
    import_anchor = "import org.yuzu.yuzu_emu.fragments.MessageDialogFragment\n"
    if text.count(import_anchor) != 1:
        raise RuntimeError("Could not identify SettingsFragmentPresenter import block")
    text = text.replace(
        import_anchor,
        import_anchor
        + "import com.nex.nx.nextendo.NextendoAuthManager\n"
        + "import com.nex.nx.nextendo.NextendoSessionStore\n",
        1,
    )

    settings_anchor = "    private fun addConfigSettings(sl: ArrayList<SettingsItem>) {\n        sl.apply {\n"
    if text.count(settings_anchor) != 1:
        raise RuntimeError("Could not identify the root settings list")
    session_row = """    private fun addConfigSettings(sl: ArrayList<SettingsItem>) {
        sl.apply {
            val nextendoUsername = runCatching {
                NextendoSessionStore.load(context)?.username.orEmpty()
            }.getOrDefault("")
            add(
                RunnableSetting(
                    titleString = "Nextendo Network",
                    descriptionString = if (nextendoUsername.isBlank()) {
                        "Entrar na conta Nextendo (perfil em leitura)"
                    } else {
                        "Conectado como $nextendoUsername — tocar para abrir perfil"
                    },
                    isRunnable = activity != null,
                    iconId = R.drawable.ic_network
                ) {
                    activity?.let { NextendoAuthManager.openAccount(it) }
                }
            )
"""
    text = text.replace(settings_anchor, session_row, 1)
    presenter.write_text(text, encoding="utf-8")

    # Den2-inspired compact Nextendo entry; NEX keeps its own OAuth/session implementation.
    games_layout = app_dir / "src/main/res/layout/fragment_games.xml"
    layout_text = games_layout.read_text(encoding="utf-8")
    settings_anchor = '        <com.google.android.material.card.MaterialCardView\n            android:id="@+id/settings_button"'
    friends_entry = """        <com.google.android.material.card.MaterialCardView
            android:id="@+id/friends_button"
            style="@style/EdenCard"
            android:layout_width="42dp"
            android:layout_height="42dp"
            android:clickable="true"
            android:focusable="true"
            android:contentDescription="Nextendo friends and account"
            app:cardCornerRadius="21dp"
            android:padding="8dp">

            <ImageView
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_gravity="center"
                android:src="@drawable/ic_account_circle"
                app:tint="#4CAF50" />

        </com.google.android.material.card.MaterialCardView>

        <Space
            android:layout_width="15dp"
            android:layout_height="wrap_content" />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/settings_button\""""
    if layout_text.count(settings_anchor) != 1:
        raise RuntimeError("Could not identify Eden settings toolbar button")
    layout_text = layout_text.replace(settings_anchor, friends_entry, 1)
    games_layout.write_text(layout_text, encoding="utf-8")

    # Eden ships a separate fragment_games.xml for landscape. Keep the
    # Nextendo entry in both resource qualifiers so rotation never removes it.
    games_layout_land = app_dir / "src/main/res/layout-land/fragment_games.xml"
    land_layout_text = games_layout_land.read_text(encoding="utf-8")
    landscape_gap = 'android:layout_width="15dp"'
    if land_layout_text.count(landscape_gap) != 2:
        raise RuntimeError("Unexpected landscape Games toolbar spacing")
    land_layout_text = land_layout_text.replace(
        landscape_gap, 'android:layout_width="8dp"'
    )
    land_settings_anchor = '        <com.google.android.material.card.MaterialCardView\n            android:id="@+id/settings_button"'
    land_friends_entry = '''        <com.google.android.material.card.MaterialCardView
            android:id="@+id/friends_button"
            style="@style/EdenCard"
            android:layout_width="42dp"
            android:layout_height="42dp"
            android:clickable="true"
            android:focusable="true"
            android:contentDescription="Nextendo friends and account"
            app:cardCornerRadius="21dp"
            android:padding="8dp">

            <ImageView
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_gravity="center"
                android:src="@drawable/ic_account_circle"
                app:tint="#4CAF50" />

        </com.google.android.material.card.MaterialCardView>

        <Space
            android:layout_width="8dp"
            android:layout_height="wrap_content" />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/settings_button"'''
    if land_layout_text.count(land_settings_anchor) != 1:
        raise RuntimeError("Could not identify landscape Games settings button")
    land_layout_text = land_layout_text.replace(
        land_settings_anchor, land_friends_entry, 1
    )
    games_layout_land.write_text(land_layout_text, encoding="utf-8")

    # Replace the inherited Eden/Yuzu logo shown above the Home Settings menu
    # with the NEX brand asset copied to drawable-nodpi earlier in this overlay.
    home_settings_layout = app_dir / "src/main/res/layout/fragment_home_settings.xml"
    replace_once(
        home_settings_layout,
        'android:src="@drawable/ic_yuzu"',
        'android:src="@drawable/nex_logo"',
    )

    games_fragment = app_dir / "src/main/java/org/yuzu/yuzu_emu/ui/GamesFragment.kt"
    games_text = games_fragment.read_text(encoding="utf-8")
    games_import_anchor = "import org.yuzu.yuzu_emu.model.HomeViewModel\n"
    if games_text.count(games_import_anchor) != 1:
        raise RuntimeError("Could not identify GamesFragment imports")
    games_imports = (
        "import androidx.lifecycle.Lifecycle\n"
        "import androidx.lifecycle.lifecycleScope\n"
        "import androidx.lifecycle.repeatOnLifecycle\n"
        "import com.google.android.material.dialog.MaterialAlertDialogBuilder\n"
        "import com.nex.nx.nextendo.NextendoAuthClient\n"
        "import com.nex.nx.nextendo.NextendoAuthManager\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.delay\n"
        "import kotlinx.coroutines.isActive\n"
        "import kotlinx.coroutines.launch\n"
        "import kotlinx.coroutines.withContext\n"
    )
    games_text = games_text.replace(games_import_anchor, games_import_anchor + games_imports, 1)

    view_click_anchor = "        binding.viewButton.setOnClickListener { showViewMenu(it) }\n"
    if games_text.count(view_click_anchor) != 1:
        raise RuntimeError("Could not identify GamesFragment toolbar setup")
    games_text = games_text.replace(
        view_click_anchor,
        view_click_anchor + "        binding.friendsButton?.setOnClickListener { showNextendoMenu() }\n",
        1
    )

    setup_anchor = "        setupTopView()\n\n        updateButtonsVisibility()\n"
    if games_text.count(setup_anchor) != 1:
        raise RuntimeError("Could not find GamesFragment setup anchor")
    games_text = games_text.replace(
        setup_anchor,
        "        setupTopView()\n        startNextendoOnlineCountsPolling()\n\n        updateButtonsVisibility()\n",
        1
    )

    navigation_anchor = "    private fun navigateToSettings() {\n"
    if games_text.count(navigation_anchor) != 1:
        raise RuntimeError("Could not identify GamesFragment navigation")
    nextendo_methods = "    private fun showNextendoMenu() {\n        val host = requireActivity()\n        MaterialAlertDialogBuilder(host)\n            .setTitle(\"Nextendo Network\")\n            .setItems(arrayOf(\"Amigos\", \"Conta Nextendo\", \"Configurar rede\")) { _, which ->\n                when (which) {\n                    0 -> NextendoAuthManager.openFriends(host)\n                    1 -> NextendoAuthManager.openAccount(host)\n                    2 -> NextendoAuthManager.openNetworkSettings(host)\n                }\n            }\n            .setNegativeButton(android.R.string.cancel, null)\n            .show()\n    }\n\n    private fun startNextendoOnlineCountsPolling() {\n        viewLifecycleOwner.lifecycleScope.launch {\n            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {\n                while (isActive) {\n                    val counts = withContext(Dispatchers.IO) {\n                        runCatching { NextendoAuthClient.fetchOnlineCounts() }.getOrNull()\n                    }\n                    if (counts != null && _binding != null) {\n                        gameAdapter.setNextendoOnlineCounts(counts)\n                    }\n                    delay(30_000L)\n                }\n            }\n        }\n    }\n\n"
    games_text = games_text.replace(navigation_anchor, nextendo_methods + navigation_anchor, 1)
    games_fragment.write_text(games_text, encoding="utf-8")

    # Adapt Den2's per-title badges while retaining Eden's game-card layouts.
    game_adapter = app_dir / "src/main/java/org/yuzu/yuzu_emu/adapters/GameAdapter.kt"
    adapter_text = game_adapter.read_text(encoding="utf-8")
    state_anchor = "    private var viewType = 0\n"
    state_replacement = """    private var viewType = 0

    private var nextendoOnlineCounts: Map<String, Int> = emptyMap()

    fun setNextendoOnlineCounts(counts: Map<String, Int>) {
        if (counts != nextendoOnlineCounts) {
            nextendoOnlineCounts = counts
            notifyDataSetChanged()
        }
    }
"""
    if adapter_text.count(state_anchor) != 1:
        raise RuntimeError("Could not identify GameAdapter state")
    adapter_text = adapter_text.replace(state_anchor, state_replacement, 1)
    holder_anchor = "        private fun bindListView(model: Game) {\n"
    if adapter_text.count(holder_anchor) != 1:
        raise RuntimeError("Could not identify GameAdapter holder")
    display_helper = "        private fun displayTitle(model: Game): CharSequence {\n            val base = model.title.replace(\"[\\\\t\\\\n\\\\r]+\".toRegex(), \" \")\n            val titleId = model.programId.toLongOrNull()?.toString(16)?.padStart(16, '0').orEmpty()\n            val onlineCount = nextendoOnlineCounts[titleId] ?: 0\n            val needsSmm2Version = titleId == \"01009b90006dc000\" &&\n                model.version.isNotBlank() && model.version != \"3.0.3\"\n\n            if (onlineCount <= 0 && !needsSmm2Version) return base\n\n            val result = android.text.SpannableStringBuilder(base)\n            if (needsSmm2Version) {\n                val badge = \"⚠ Testada: 3.0.3\"\n                val start = result.length + 2\n                result.append(\"  \").append(badge)\n                result.setSpan(\n                    android.text.style.ForegroundColorSpan(0xFFFF9800.toInt()),\n                    start, start + badge.length,\n                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE\n                )\n            }\n            if (onlineCount > 0) {\n                val badge = \"• \" + onlineCount + \" online\"\n                val start = result.length + 2\n                result.append(\"  \").append(badge)\n                result.setSpan(\n                    android.text.style.ForegroundColorSpan(0xFF4CAF50.toInt()),\n                    start, start + badge.length,\n                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE\n                )\n            }\n            return result\n        }\n\n"
    adapter_text = adapter_text.replace(holder_anchor, display_helper + holder_anchor, 1)

    replacements = (
        (r'listBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")',
         "listBinding.textGameTitle.text = displayTitle(model)"),
        (r'gridBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")',
         "gridBinding.textGameTitle.text = displayTitle(model)"),
        (r'gridCompactBinding.textGameTitleCompact.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")',
         "gridCompactBinding.textGameTitleCompact.text = displayTitle(model)"),
        (r'carouselBinding.textGameTitle.text = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")',
         "carouselBinding.textGameTitle.text = displayTitle(model)"),
    )
    for old_assignment, new_assignment in replacements:
        if adapter_text.count(old_assignment) != 1:
            raise RuntimeError("Unexpected GameAdapter title assignment")
        adapter_text = adapter_text.replace(old_assignment, new_assignment, 1)
    game_adapter.write_text(adapter_text, encoding="utf-8")

    print("NEX Den2-inspired toolbar menu and per-game badges applied")

    # Publish real in-game presence from Eden's existing title-ID/playtime callback.
    emulation_activity = (
        app_dir / "src/main/java/org/yuzu/yuzu_emu/activities/EmulationActivity.kt"
    )
    activity_text = emulation_activity.read_text(encoding="utf-8")
    activity_import = "import org.yuzu.yuzu_emu.NativeLibrary\n"
    if activity_text.count(activity_import) != 1:
        raise RuntimeError("Could not identify EmulationActivity NativeLibrary import")
    activity_text = activity_text.replace(
        activity_import,
        activity_import
        + "import com.nex.nx.nextendo.NextendoPresencePublisher\n"
        + "import com.nex.nx.nextendo.NextendoAuthManager\n",
        1,
    )
    activity_on_create_anchor = "        super.onCreate(savedInstanceState)\n"
    if activity_text.count(activity_on_create_anchor) != 1:
        raise RuntimeError("Could not identify EmulationActivity startup callback")
    activity_text = activity_text.replace(
        activity_on_create_anchor,
        activity_on_create_anchor
        + "        NextendoAuthManager.restoreCachedIdentity(this)\n",
        1,
    )

    lifecycle_anchor = """    fun onEmulationStarted() {
        emulationViewModel.setEmulationStarted(true)
        NativeLibrary.playTimeManagerStart()

    }

    fun onEmulationStopped(status: Int) {
        if (status == 0 && emulationViewModel.programChanged.value == -1) {
"""
    lifecycle_replacement = """    fun onEmulationStarted() {
        emulationViewModel.setEmulationStarted(true)
        NativeLibrary.playTimeManagerStart()

        val currentTitleId = NativeLibrary.playTimeManagerGetCurrentTitleId()
        if (currentTitleId > 0L) {
            val titleId = java.lang.String.format(java.util.Locale.ROOT, "%016x", currentTitleId)
            if (titleId.equals("0100000000001000", ignoreCase = true)) {
                NextendoPresencePublisher.setHomeMenuActivity(this)
            } else {
                NextendoPresencePublisher.setGameActivity(this, titleId, "Playing")
            }
        } else {
            NextendoPresencePublisher.clearGameActivity(this)
        }
    }

    fun onEmulationStopped(status: Int) {
        NextendoPresencePublisher.clearGameActivity(this)
        if (status == 0 && emulationViewModel.programChanged.value == -1) {
"""
    if activity_text.count(lifecycle_anchor) != 1:
        raise RuntimeError("Could not identify EmulationActivity lifecycle callbacks")
    activity_text = activity_text.replace(lifecycle_anchor, lifecycle_replacement, 1)
    emulation_activity.write_text(activity_text, encoding="utf-8")

    print(f"NEX overlay applied successfully to Eden {commit}")
    print(f"Android applicationId: com.nex.nx")
    print(f"OAuth redirect URI: com.nex.nx://oauth/callback")
    print(f"Copied {len(list(kotlin_destination.glob('*.kt')))} NEX Kotlin source files")


if __name__ == "__main__":
    main()
