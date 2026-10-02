/*
 * Modern UI.
 * Copyright (C) 2019-2022 BloCamLimb. All rights reserved.
 *
 * Modern UI is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Modern UI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Modern UI. If not, see <https://www.gnu.org/licenses/>.
 */

package icyllis.modernui.mc.text.mixin;

import icyllis.modernui.mc.text.TextLayoutEngine;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(FontManager.class)
public class MixinFontManager {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void modernuiReload(@Nonnull PreparableReloadListener.SharedState currentReload,
                                          @Nonnull Executor preparationExecutor,
                                          @Nonnull PreparableReloadListener.PreparationBarrier preparationBarrier,
                                          @Nonnull Executor reloadExecutor,
                                          CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (TextLayoutEngine.usesResourcePackTextLayout(currentReload.resourceManager())) return;
        cir.setReturnValue(TextLayoutEngine.getInstance().injectFontManager((FontManager) (Object) this)
                .reload(currentReload,
                        preparationExecutor,
                        preparationBarrier,
                        reloadExecutor));
    }

    @Inject(method = "reload", at = @At("RETURN"), cancellable = true)
    private void modernuiReloadAfterVanilla(@Nonnull PreparableReloadListener.SharedState currentReload,
                                           @Nonnull Executor preparationExecutor,
                                           @Nonnull PreparableReloadListener.PreparationBarrier preparationBarrier,
                                           @Nonnull Executor reloadExecutor,
                                           CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (!TextLayoutEngine.usesResourcePackTextLayout(currentReload.resourceManager())) return;
        // Vanilla owns its provider/atlas lifecycle. Load ModernUI's separate resources
        // afterward, without waiting on the shared preparation barrier a second time.
        cir.setReturnValue(cir.getReturnValue().thenCompose(unused ->
                TextLayoutEngine.getInstance().injectFontManager((FontManager) (Object) this)
                        .reload(currentReload, preparationExecutor,
                                new PreparableReloadListener.PreparationBarrier() {
                                    @Override
                                    public <T> CompletableFuture<T> wait(T prepared) {
                                        return CompletableFuture.completedFuture(prepared);
                                    }
                                }, reloadExecutor)));
    }
}
