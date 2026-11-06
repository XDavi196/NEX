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
    settings_button_anchor = """        <com.google.android.material.card.MaterialCardView
            android:id="@+id/settings_button"
"""
    nextendo_button = """        <Space
            android:layout_width="15dp"
            android:layout_height="wrap_content"
            />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/nextendo_button"
            style="@style/EdenCard"
            android:layout_width="42dp"
            android:layout_height="42dp"
            android:background="@android:color/transparent"
            android:contentDescription="Nextendo Network"
            android:tooltipText="Nextendo Network"
            android:clickable="true"
            android:focusable="true"
            app:cardCornerRadius="21dp"
            app:cardBackgroundColor="@android:color/transparent"
            app:cardElevation="0dp"
            app:strokeColor="?attr/colorOutline"
            app:strokeWidth="1dp"
            android:padding="8dp"
            >

            <ImageView
                android:layout_width="18dp"
                android:layout_height="18dp"
                android:layout_gravity="center"
                android:src="@drawable/ic_account_circle"
                app:tint="#4CAF50"
                />

        </com.google.android.material.card.MaterialCardView>

        <Space
            android:layout_width="15dp"
            android:layout_height="wrap_content"
            />

""" + settings_button_anchor
    if layout_text.count(settings_button_anchor) != 1:
        raise RuntimeError("Could not identify the games header settings button")
    layout_text = layout_text.replace(settings_button_anchor, nextendo_button, 1)
    games_layout.write_text(layout_text, encoding="utf-8")

    # The home-screen shortcut opens the NEX account panel: sign in when there is
    # no session; otherwise show the profile and its Friends action.
    games_fragment = (
        app_dir / "src/main/java/org/yuzu/yuzu_emu/ui/GamesFragment.kt"
    )
    games_text = games_fragment.read_text(encoding="utf-8")
    games_import_anchor = "import org.yuzu.yuzu_emu.model.HomeViewModel\\n"
    if games_text.count(games_import_anchor) != 1:
        raise RuntimeError("Could not identify GamesFragment import block")
    games_text = games_text.replace(
        games_import_anchor,
        games_import_anchor
        + "import com.nex.nx.nextendo.NextendoAuthManager\\n",
        1,
    )
    view_click_anchor = "        binding.viewButton.setOnClickListener { showViewMenu(it) }\\n"
    if games_text.count(view_click_anchor) != 1:
        raise RuntimeError("Could not identify GamesFragment top toolbar setup")
    games_text = games_text.replace(
        view_click_anchor,
        view_click_anchor
        + """        binding.nextendoButton.setOnClickListener {
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
