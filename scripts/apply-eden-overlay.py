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
        "    external fun updateNextendoFriendCache(pids: LongArray, nsas: LongArray, usernames: Array<String>, statuses: IntArray, appFields: Array<ByteArray>)\n"
        "    external fun clearNextendoFriendCache()\n"
        "    external fun updateNextendoIdentity(pid: Long)\n"
        "    external fun clearNextendoIdentity()\n"
        "    external fun getAllUsers(): Array<String>?\n",
    )

    # Copy the NEX-owned bounded cache and expose only PID/name records to JNI.
    native_header_source = NEX_SOURCE / "integration/android/src/main/cpp/nextendo_friend_cache.h"
    native_header_destination = (
        eden / "src/core/hle/service/friend/nextendo_friend_cache.h"
    )
    if not native_header_source.is_file():
        raise RuntimeError(f"Missing NEX native friend cache header: {native_header_source}")
    native_header_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(native_header_source, native_header_destination)

    native_cpp = app_dir / "src/main/jni/native.cpp"
    replace_once(
        native_cpp,
        '#include "core/hle/service/filesystem/filesystem.h"\n',
        '#include "core/hle/service/filesystem/filesystem.h"\n'
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n',
    )
    native_jni_anchor = """JNIEXPORT jobjectArray JNICALL Java_org_yuzu_yuzu_1emu_NativeLibrary_getAllUsers(
        JNIEnv* env,
        [[maybe_unused]] jobject obj) {
"""
    native_jni_bridge = r'''JNIEXPORT void JNICALL
JNIEXPORT void JNICALL
Java_org_yuzu_yuzu_1emu_NativeLibrary_updateNextendoFriendCache(
    JNIEnv* env, [[maybe_unused]] jobject obj, jlongArray pids, jlongArray nsas,
    jobjectArray usernames, jintArray statuses, jobjectArray app_fields) {
    if (pids == nullptr || nsas == nullptr || usernames == nullptr ||
        statuses == nullptr || app_fields == nullptr) {
        Common::NextendoFriendCache::Clear();
        return;
    }

    const jsize pid_count = env->GetArrayLength(pids);
    const jsize nsa_count = env->GetArrayLength(nsas);
    const jsize name_count = env->GetArrayLength(usernames);
    const jsize status_count = env->GetArrayLength(statuses);
    const jsize field_count = env->GetArrayLength(app_fields);
    const jsize count = std::min<jsize>(
        std::min(std::min(pid_count, nsa_count), std::min(name_count, status_count)),
        std::min(field_count, static_cast<jsize>(Common::NextendoFriendCache::MaxFriends)));

    std::vector<jlong> pid_values(static_cast<std::size_t>(count));
    std::vector<jlong> nsa_values(static_cast<std::size_t>(count));
    std::vector<jint> status_values(static_cast<std::size_t>(count));
    if (count > 0) {
        env->GetLongArrayRegion(pids, 0, count, pid_values.data());
        env->GetLongArrayRegion(nsas, 0, count, nsa_values.data());
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
Java_org_yuzu_yuzu_emu_NativeLibrary_clearNextendoIdentity(
    JNIEnv* /*env*/, [[maybe_unused]] jobject obj) {
    Common::NextendoFriendCache::ClearPlayerPid();
}

'''
    replace_once(native_cpp, native_jni_anchor, native_jni_bridge + native_jni_anchor)

    # ACC exposes the linked NEX PID as NetworkServiceAccountId to games.
    acc_cpp = eden / "src/core/hle/service/acc/acc.cpp"
    replace_once(
        acc_cpp,
        '#include "core/hle/service/acc/acc.h"\n',
        '#include "core/hle/service/acc/acc.h"\n'
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n',
    )
    replace_once(
        acc_cpp,
        """    Result GetAccountId(Out<u64> out_account_id) {
        LOG_WARNING(Service_ACC, "(STUBBED) called");
        *out_account_id = account_id.Hash();
        R_SUCCEED();
    }""",
        """    Result GetAccountId(Out<u64> out_account_id) {
        const auto nex_pid = Common::NextendoFriendCache::GetPlayerPid();
        *out_account_id = nex_pid != 0 ? nex_pid : account_id.Hash();
        LOG_INFO(Service_ACC, "[NEX Nextendo] GetAccountId -> {}",
                 *out_account_id);
        R_SUCCEED();
    }""",
    )
    replace_once(
        acc_cpp,
        """    void GetAccountId(HLERequestContext& ctx) {
        LOG_DEBUG(Service_ACC, "called");

        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.PushRaw<u64>(profile_manager->GetLastOpenedUser().Hash());
    }""",
        """    void GetAccountId(HLERequestContext& ctx) {
        const auto nex_pid = Common::NextendoFriendCache::GetPlayerPid();
        const auto account_id = nex_pid != 0
            ? nex_pid
            : profile_manager->GetLastOpenedUser().Hash();
        LOG_INFO(Service_ACC, "[NEX Nextendo] GetAccountId -> {}", account_id);
        IPC::ResponseBuilder rb{ctx, 4};
        rb.Push(ResultSuccess);
        rb.PushRaw<u64>(account_id);
    }""",
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
        '#include "core/hle/service/friend/nextendo_friend_cache.h"\n',
    )
    friend_support = r'''// FriendInfo/ProfileInfo ABI layout follows the pinned Eden HLE and the publicly
// documented Nextendo/Den2 friend-service wire layout. No Den2 identity or secrets
// are used; NEX supplies its own PID/name cache through the JNI bridge.
namespace {
#pragma pack(push, 1)
struct NextendoUserPresence {
    Common::UUID user_id;
    s64 last_time_online_timestamp;
    u32 status;
    u8 same_presence_group_application;
    std::array<u8, 3> unknown;
    std::array<u8, 0xC0> app_key_value;
};
static_assert(sizeof(NextendoUserPresence) == 0xE0);

struct NextendoFriendInfo {
    Common::UUID user_id;
    u64 network_user_id;
    std::array<char, 0x21> nickname;
    std::array<u8, 7> nickname_padding;
    NextendoUserPresence presence;
    u8 is_favourite;
    u8 is_new;
    std::array<u8, 6> unknown;
    u8 is_valid;
    std::array<u8, 0xD7> padding;
};
static_assert(sizeof(NextendoFriendInfo) == 0x200);
static_assert(offsetof(NextendoFriendInfo, presence) == 0x40);
static_assert(offsetof(NextendoFriendInfo, is_favourite) == 0x120);
static_assert(offsetof(NextendoFriendInfo, is_valid) == 0x128);

struct NextendoProfileInfo {
    Common::UUID user_uuid;
    u64 network_user_id;
    std::array<char, 0x21> nickname;
    std::array<u8, 7> padding;
};
static_assert(sizeof(NextendoProfileInfo) == 0x40);
#pragma pack(pop)

Common::UUID NextendoUidForPid(u64 pid) {
    std::array<u8, 16> raw{};
    std::memcpy(raw.data(), &pid, sizeof(pid));
    constexpr u64 uid_namespace = 0x1100000000000000ULL;
    std::memcpy(raw.data() + sizeof(pid), &uid_namespace, sizeof(uid_namespace));
    return Common::UUID{raw};
}

NextendoFriendInfo MakeNextendoFriendInfo(
    const Common::NextendoFriendCache::Entry& entry) {
    NextendoFriendInfo out{};
    out.user_id = NextendoUidForPid(entry.pid);
    out.network_user_id = entry.pid;
    const auto length = std::min(entry.username.size(), out.nickname.size() - 1);
    std::memcpy(out.nickname.data(), entry.username.data(), length);
    out.presence.user_id = out.user_id;
    out.presence.status = entry.presence_status;
    out.presence.last_time_online_timestamp = 0x7FFFFFFFFFFFFFFFLL;
    // This marks the friend as sharing the same title presence group, matching
    // the observed Nextendo HLE behavior. app_field is copied byte-for-byte.
    out.presence.same_presence_group_application = 1;
    const auto app_field_size = std::min(
        entry.app_field_size, out.presence.app_key_value.size());
    std::copy_n(
        entry.app_field.begin(), app_field_size,
        out.presence.app_key_value.begin());
    out.is_valid = 1;
    return out;
}

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
    const auto friends = Common::NextendoFriendCache::Get();
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
            [pid](const auto& entry) { return entry.pid == pid; });
        if (found == friends.end()) {
            continue;
        }

        NextendoProfileInfo profile{};
        profile.user_uuid = NextendoUidForPid(found->pid);
        profile.network_user_id = found->pid;
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
    const auto friends = Common::NextendoFriendCache::Get();
    const auto capacity = std::min(
        ctx.GetWriteBufferNumElements<NextendoFriendInfo>(),
        Common::NextendoFriendCache::MaxFriends);
    const auto count = std::min(requested.size(), capacity);

    std::vector<NextendoFriendInfo> details;
    details.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        const auto pid = requested[i];
        const auto found = std::find_if(friends.begin(), friends.end(),
            [pid](const auto& entry) { return entry.pid == pid; });
        details.push_back(found == friends.end() ? NextendoFriendInfo{}
                                                 : MakeNextendoFriendInfo(*found));
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
        '{20102, nullptr, "GetFriendDetailedInfo"}',
        '{20102, &IFriendService::GetFriendDetailedInfo, "GetFriendDetailedInfo"}',
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
        const auto uuid = rp.PopRaw<Common::UUID>();
        const auto friends = Common::NextendoFriendCache::Get();
        NextendoUserPresence presence{};
        const auto found = std::find_if(friends.begin(), friends.end(),
            [&uuid](const auto& entry) {
                return NextendoUidForPid(entry.pid).RawString() == uuid.RawString();
            });
        if (found != friends.end()) {
            presence.user_id = NextendoUidForPid(found->pid);
            presence.status = found->presence_status;
            presence.last_time_online_timestamp = 0x7FFFFFFFFFFFFFFFLL;
            presence.same_presence_group_application = 1;
            const auto app_field_size = std::min(
                found->app_field_size, presence.app_key_value.size());
            std::copy_n(
                found->app_field.begin(), app_field_size,
                presence.app_key_value.begin());
        }
        ctx.WriteBuffer(presence);
        LOG_INFO(Service_Friend, "[NEX Nextendo] GetUserPresenceView uuid=0x{} found={}",
                 uuid.RawString(), found != friends.end());
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

        const auto friends = Common::NextendoFriendCache::Get();
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
        const auto friends = Common::NextendoFriendCache::Get();
        const auto capacity = ctx.GetWriteBufferNumElements<u64>();
        std::vector<u64> pids;
        if (offset == 0) {
            pids.reserve(std::min(friends.size(), capacity));
            for (const auto& entry : friends) {
                if (pids.size() >= capacity) {
                    break;
                }
                pids.push_back(entry.pid);
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
        const auto count = static_cast<u32>(Common::NextendoFriendCache::Get().size());
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
        const auto friends = Common::NextendoFriendCache::Get();
        const auto capacity = ctx.GetWriteBufferNumElements<u64>();
        std::vector<u64> pids;
        if (offset == 0) {
            pids.reserve(std::min(friends.size(), capacity));
            for (const auto& entry : friends) {
                if (pids.size() >= capacity) {
                    break;
                }
                pids.push_back(entry.pid);
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

    logo_source = NEX_SOURCE / "assets/nex-logo.jpg"
    if not logo_source.is_file():
        raise RuntimeError(f"Missing NEX logo asset: {logo_source}")
    logo_destination = app_dir / "src/main/res/drawable-nodpi/nex_logo.jpg"
    logo_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(logo_source, logo_destination)

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
        'android:icon="@drawable/nex_logo"',
    )
    replace_once(
        manifest,
        'android:roundIcon="@mipmap/ic_launcher_round"',
        'android:roundIcon="@drawable/nex_logo"',
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
            NextendoPresencePublisher.setGameActivity(this, titleId, "Playing")
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
