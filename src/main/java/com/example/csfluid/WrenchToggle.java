package com.example.csfluid;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** ПКМ гаечным ключом Create по двигателю = переключить авто-режим этого двигателя. */
public final class WrenchToggle {
    private WrenchToggle() {}

    private static final ResourceLocation WRENCH_ID = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!CsFluid.wrenchToggle()) return;

        Player player = event.getEntity();
        if (player.isShiftKeyDown()) return; // присед + ключ = как обычно (разобрать блок)

        ItemStack stack = event.getItemStack();
        if (!WRENCH_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return;

        BlockEntity be = level.getBlockEntity(event.getPos());
        if (!(be instanceof AutoToggle toggle)) return;

        boolean now = !toggle.csfIsAuto();
        toggle.csfSetAuto(now);
        be.setChanged();

        Component state = Component.literal(now ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН")
                .withStyle(now ? ChatFormatting.GREEN : ChatFormatting.RED);
        player.displayClientMessage(
                Component.literal("Авто-режим нагрузки: ").withStyle(ChatFormatting.GRAY).append(state), true);

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
