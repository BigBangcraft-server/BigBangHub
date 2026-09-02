package com.bigbangcraft.hub.common;

public record ProtectionSettings(boolean blockBreak, boolean blockPlace, boolean itemDrop, boolean itemPickup,
                                 boolean damage, boolean pvp, boolean hunger, boolean mobInteractions,
                                 boolean crafting, boolean inventoryManipulation, boolean weather,
                                 boolean farmlandTrampling, boolean armorStandInteraction, boolean entityInteraction,
                                 boolean bucketUse, boolean fire, boolean explosions, boolean fluidPlacement,
                                 boolean voidSafety) { }
