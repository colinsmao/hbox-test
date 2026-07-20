package dev.kelianmao.mobwalk.client.config;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.screens.Screen;

import fi.dy.masa.malilib.gui.interfaces.IConfigGui;
import fi.dy.masa.malilib.gui.interfaces.IDialogHandler;

/** Built-in profiles table editor ({@link ProfilesTableEditEntry} — per-row RESET inactive). */
final class BuiltinProfilesTableEdit extends ProfilesTableEdit {
  BuiltinProfilesTableEdit(
    IConfigGui configGui,
    @Nullable IDialogHandler dialogHandler,
    Screen parent
  ) {
    super(Configs.Profiles.BUILTIN_PROFILES, configGui, dialogHandler, parent);
  }
}
