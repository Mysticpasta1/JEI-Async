package mezz.jei.forge.mixin;

import com.lowdragmc.lowdraglib.client.scene.ISceneBlockRenderHook;
import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Set;

@Mixin(value = SceneWidget.class, remap = false)
public class SceneWidgetMixin {
	@Shadow
	protected Set<?> core;

	@Inject(
		method = "setRenderedCore(Ljava/util/Collection;Lcom/lowdragmc/lowdraglib/client/scene/ISceneBlockRenderHook;)Lcom/lowdragmc/lowdraglib/gui/widget/SceneWidget;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void onSetRenderedCore(Collection<BlockPos> blocks, ISceneBlockRenderHook renderHook, CallbackInfoReturnable<SceneWidget> cir) {
		if (this.core == null) {
			cir.cancel();
		}
	}
}
