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
        '    implementation("androidx.browser:browser:1.8.0")',
    )

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
                        "Conectar conta, perfil, amigos e multiplayer"
                    } else {
                        "Conectado como $nextendoUsername — tocar para reconectar"
                    },
                    isRunnable = activity != null,
                    iconId = R.drawable.ic_network
                ) {
                    activity?.let { NextendoAuthManager.startLogin(it) }
                }
            )
"""
    text = text.replace(settings_anchor, session_row, 1)
    presenter.write_text(text, encoding="utf-8")

    print(f"NEX overlay applied successfully to Eden {commit}")
    print(f"Android applicationId: com.nex.nx")
    print(f"OAuth redirect URI: com.nex.nx://oauth/callback")
    print(f"Copied {len(list(kotlin_destination.glob('*.kt')))} NEX Kotlin source files")


if __name__ == "__main__":
    main()
