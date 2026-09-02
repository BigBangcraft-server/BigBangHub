package com.bigbangcraft.hub.common;

public record InventorySettings(boolean clearOnJoin, boolean lockLobbyItems, boolean preventDrop,
                                boolean preventMove) { }
