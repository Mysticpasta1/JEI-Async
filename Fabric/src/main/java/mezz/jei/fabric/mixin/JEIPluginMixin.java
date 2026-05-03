package mezz.jei.fabric.mixin;

import net.mehvahdjukaar.jeed.plugin.jei.JEIPlugin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JEIPlugin.class)
public class JEIPluginMixin {
	@Inject(
		method = "onClickedEffect",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private void onBeforeClickedEffect(CallbackInfo ci) {
		if (JEIPlugin.JEI_HELPERS == null) {
			ci.cancel();
			return;
		}
		if (JEIPlugin.JEI_RUNTIME == null) {
			ci.cancel();
		}
	}
}
