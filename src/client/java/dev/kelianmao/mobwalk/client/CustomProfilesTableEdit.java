package dev.kelianmao.mobwalk.client;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.screens.Screen;

import fi.dy.masa.malilib.gui.interfaces.IConfigGui;
import fi.dy.masa.malilib.gui.interfaces.IDialogHandler;

/** Custom-profiles table editor ({@link ProfilesTableEditEntry} — per-row RESET inactive). */
final class CustomProfilesTableEdit extends ProfilesTableEdit {
  CustomProfilesTableEdit(
    IConfigGui configGui,
    @Nullable IDialogHandler dialogHandler,
    Screen parent
  ) {
    super(Configs.Profiles.CUSTOM_PROFILES, configGui, dialogHandler, parent);
  }
}
