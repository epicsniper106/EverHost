package dev.everhost.server.mixin;

import org.spongepowered.asm.mixin.Mixin;

/** Restores the removed intermediary hook referenced by e4mc on dedicated 1.21.11 servers. */
@Mixin(targets = "net.minecraft.class_2168", remap = false)
abstract class E4mcCommandSourceCompatibilityMixin {
	public boolean method_9259(int permissionLevel) {
		// EverHost owns tunnel controls, so e4mc's dedicated-server admin commands stay hidden.
		return false;
	}
}
