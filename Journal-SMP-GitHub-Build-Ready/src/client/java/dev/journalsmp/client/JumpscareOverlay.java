package dev.journalsmp.client;

import dev.journalsmp.JournalSmpMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

public final class JumpscareOverlay {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            JournalSmpMod.MOD_ID, "textures/gui/vanilte_jumpscare.png");
    private static long expiresAtMillis;

    private JumpscareOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(JournalSmpMod.MOD_ID, "jumpscare_overlay"),
                (graphics, tickCounter) -> render(graphics));
    }

    public static void activate(int durationMillis) {
        expiresAtMillis = durationMillis <= 0 ? 0L : System.currentTimeMillis() + durationMillis;
    }

    private static void render(GuiGraphics graphics) {
        if (expiresAtMillis == 0L) {
            return;
        }
        if (System.currentTimeMillis() >= expiresAtMillis) {
            expiresAtMillis = 0L;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getResourceManager().getResource(TEXTURE).isEmpty()) {
            expiresAtMillis = 0L;
            return;
        }
        graphics.blit(TEXTURE, 0, 0, graphics.guiWidth(), graphics.guiHeight(),
                0.0F, 0.0F, 1.0F, 1.0F);
    }
}
