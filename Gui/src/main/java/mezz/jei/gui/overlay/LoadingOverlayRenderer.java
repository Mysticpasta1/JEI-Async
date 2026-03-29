package mezz.jei.gui.overlay;

import mezz.jei.common.Internal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

public class LoadingOverlayRenderer {

	public static void renderLoadingOverlay(Screen screen, GuiGraphics guiGraphics) {
		@Nullable String progress = Internal.getLoadingProgress();
		if (progress == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;

		String text = "JEI: " + progress;
		int textWidth = font.width(text);
		int screenWidth = screen.width;
		int x = screenWidth - textWidth - 4;
		int y = 4;

		// Draw background
		guiGraphics.fill(x - 3, y - 2, x + textWidth + 3, y + font.lineHeight + 2, 0xAA000000);
		// Draw text
		guiGraphics.drawString(font, text, x, y, 0xFF55FF55, true);
	}
}
