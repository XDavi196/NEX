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

    native_cpp = app_dir / "src/main/jni/native.cpp"
    replace_once(
        native_cpp,
        '#include "core/hle/service/filesystem/filesystem.h"\n',
        '#include "core/hle/service/filesystem/filesystem.h"\n'
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n'
        '#include "core/hle/service/acc/nextendo_baas_token_cache.h"\n',
    )
    native_jni_anchor = """JNIEXPORT jobjectArray JNICALL Java_org_yuzu_yuzu_1emu_NativeLibrary_getAllUsers(
        JNIEnv* env,
        [[maybe_unused]] jobject obj) {
"""
    native_jni_bridge = r'''JNIEXPORT void JNICALL
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
    if (pid > 0) {
        Common::NextendoFriendCache::SetPlayerPid(static_cast<std::uint64_t>(pid));
    } else {
        Common::NextendoFriendCache::ClearPlayerPid();
    }
}

JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_clearNextendoIdentity(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Common::NextendoFriendCache::ClearPlayerPid();
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
        '#include "core/hle/service/acc/nextendo_baas_token_cache.h"\n',
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
    # Keep Eden's local ACC identity until Nextendo confirms the NEX PID/NSA mapping.
    # A Nextendo PID is not assumed to be a NetworkServiceAccountId.

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
        '            {20109, &IFriendService::GetFriendDetailListV2, "GetFriendDetailListV2"},\n'
        '            {20111, &IFriendService::GetFriendPresenceV2, "GetFriendPresenceV2"}',
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

    // SDK 22+: fills one 0x220-byte modern friend record for each requested PID/NSA.
    void GetFriendDetailListV2(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        [[maybe_unused]] const auto user_id = rp.PopRaw<Common::UUID>();
        const auto requested = ReadNextendoPids(ctx);
        const auto output_bytes =
            ctx.GetWriteBufferNumElements<u64>() * sizeof(u64);
        const auto capacity = output_bytes / NextendoFriendRecordV2Size;
        const auto count = std::min(requested.size(), capacity);
        const auto friends = Common::NextendoFriendCache::GetNetworkFriends();
        std::vector<u8> output(count * NextendoFriendRecordV2Size);
        for (std::size_t i = 0; i < count; ++i) {
            const auto requested_id = requested[i];
            const auto found = std::find_if(friends.begin(), friends.end(),
                [requested_id](const auto& entry) {
                    return entry.network_service_account_id == requested_id;
                });
            if (found != friends.end()) {
                WriteNextendoFriendRecordV2(
                    output.data() + i * NextendoFriendRecordV2Size, *found, requested_id);
            }
        }
        if (!output.empty()) {
            ctx.WriteBuffer(output);
        }
        LOG_INFO(Service_Friend, "[NEX Nextendo] GetFriendDetailListV2 ids={} returned={}",
                 requested.size(), count);
        IPC::ResponseBuilder rb{ctx, 2};
        rb.Push(ResultSuccess);
    }

    // The official qLaunch handler returns a zeroed 0x80-byte presence detail view.
    void GetFriendPresenceV2(HLERequestContext& ctx) {
        IPC::RequestParser rp{ctx};
        [[maybe_unused]] const auto user_id = rp.PopRaw<Common::UUID>();
        const auto friend_id = rp.Pop<u64>();
        std::array<u8, NextendoFriendPresenceV2Size> presence{};
        ctx.WriteBuffer(presence);
        LOG_INFO(Service_Friend, "[NEX Nextendo] GetFriendPresenceV2 id={}", friend_id);
        IPC::ResponseBuilder rb{ctx, 2};
        rb.Push(ResultSuccess);
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

    # Add a clearly visible Nextendo shortcut to the main games header, following
    # the placement familiar to Den2 users while keeping all authentication NEX-owned.
    games_layout = app_dir / "src/main/res/layout/fragment_games.xml"
    layout_text = games_layout.read_text(encoding="utf-8")
    title_anchor = """        <com.google.android.material.textview.MaterialTextView
            android:id="@+id/title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/app_name"
            style="@style/SynthwaveText.Title"
            android:textSize="27sp"
            />"""
    if layout_text.count(title_anchor) != 1:
        raise RuntimeError("Could not identify Eden's original games title")
    # Keep Eden's original MaterialTextView title. Gradle above sets app_name to
    # "NEX", so the header shows the wordmark instead of a large logo image.
    header_anchor = """    </LinearLayout>

    <FrameLayout
        android:id="@+id/frame_search"
"""
    nextendo_entry = """    </LinearLayout>

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/nextendo_button"
        style="@style/EdenCard"
        android:layout_width="0dp"
        android:layout_height="64dp"
        android:layout_marginHorizontal="15dp"
        android:layout_marginTop="12dp"
        android:clickable="true"
        android:focusable="true"
        android:contentDescription="Nextendo Network. Connect your account and view your friends."
        app:cardCornerRadius="16dp"
        app:cardBackgroundColor="?attr/colorSurfaceVariant"
        app:cardElevation="0dp"
        app:strokeColor="#E30613"
        app:strokeWidth="1dp"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toBottomOf="@id/header">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="16dp">

            <ImageView
                android:layout_width="30dp"
                android:layout_height="30dp"
                android:layout_marginEnd="14dp"
                android:src="@drawable/ic_account_circle"
                app:tint="#E30613" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Nextendo Network"
                    android:textColor="?attr/colorOnSurface"
                    android:textSize="14sp"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Connect your account and see your friends"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:textSize="12sp" />
            </LinearLayout>

        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <FrameLayout
        android:id="@+id/frame_search"
"""
    if layout_text.count(header_anchor) != 1:
        raise RuntimeError("Could not identify the games header/search boundary")
    layout_text = layout_text.replace(header_anchor, nextendo_entry, 1)

    frame_start = layout_text.index('    <FrameLayout\n        android:id="@+id/frame_search"')
    frame_end = layout_text.index("    >", frame_start) + len("    >")
    frame_open = layout_text[frame_start:frame_end]
    if frame_open.count('android:layout_marginTop="16dp"') != 1:
        raise RuntimeError("Could not identify the search panel top margin")
    if frame_open.count('app:layout_constraintTop_toBottomOf="@id/header"') != 1:
        raise RuntimeError("Could not identify the search panel top constraint")
    frame_open = frame_open.replace('android:layout_marginTop="16dp"', 'android:layout_marginTop="12dp"')
    frame_open = frame_open.replace(
        'app:layout_constraintTop_toBottomOf="@id/header"',
        'app:layout_constraintTop_toBottomOf="@id/nextendo_button"'
    )
    layout_text = layout_text[:frame_start] + frame_open + layout_text[frame_end:]
    games_layout.write_text(layout_text, encoding="utf-8")

    # The home-screen shortcut opens the NEX account panel: sign in when there is
    # no session; otherwise show the profile and its Friends action.
    games_fragment = (
        app_dir / "src/main/java/org/yuzu/yuzu_emu/ui/GamesFragment.kt"
    )
    games_text = games_fragment.read_text(encoding="utf-8")
    games_import_anchor = "import org.yuzu.yuzu_emu.model.HomeViewModel\n"
    if games_text.count(games_import_anchor) != 1:
        raise RuntimeError("Could not identify GamesFragment import block")
    games_text = games_text.replace(
        games_import_anchor,
        games_import_anchor
        + "import com.nex.nx.nextendo.NextendoAuthManager\n",
        1,
    )
    view_click_anchor = "        binding.viewButton.setOnClickListener { showViewMenu(it) }\n"
    if games_text.count(view_click_anchor) != 1:
        raise RuntimeError("Could not identify GamesFragment top toolbar setup")
    games_text = games_text.replace(
        view_click_anchor,
        view_click_anchor
        + """        binding.nextendoButton?.setOnClickListener {
            NextendoAuthManager.openAccount(requireActivity())
        }
""",
        1,
    )
    games_fragment.write_text(games_text, encoding="utf-8")

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
        activity_import + "import com.nex.nx.nextendo.NextendoPresencePublisher\n",
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
