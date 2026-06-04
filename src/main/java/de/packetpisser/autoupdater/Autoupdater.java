package de.packetpisser.autoupdater;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class Autoupdater implements PreLaunchEntrypoint, ModInitializer, ClientModInitializer {
    @Override
    public void onPreLaunch() {
        UpdaterCore.preLaunch();
    }

    @Override
    public void onInitialize() {
        UpdaterCore.init();
    }

    @Override
    public void onInitializeClient() {
        UpdaterCore.initClient();
    }
}
