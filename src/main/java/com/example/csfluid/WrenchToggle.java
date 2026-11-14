package com.example.csfluid;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** 
 * ПКМ гаечным ключом Create:
 *  - по двигателю = переключить авто-режим нагрузки;
 *  - по соседнему баку = разрешить/запретить двигателю выкачивать из него лаву.
 */
public final class WrenchToggle {
    private WrenchToggle() {}

    private static final ResourceLocation WRENCH_ID = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!CsFluid.wrenchToggle()) return;

        Player player = event.getEntity();
        if (player.isShiftKeyDown()) return; // присед + ключ = штатный разбор блока

        ItemStack stack = event.getItemStack();
        if (!WRENCH_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return;

        BlockPos clickedPos = event.getPos();
        BlockEntity be = level.getBlockEntity(clickedPos);

        // 1. Клик по самому двигателю: переключение авто-режима нагрузки
        if (be instanceof AutoToggle toggle) {
            boolean now = !toggle.csfIsAuto();
            toggle.csfSetAuto(now);
            be.setChanged();

            Component state = Component.literal(now ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН")
                    .withStyle(now ? ChatFormatting.GREEN : ChatFormatting.RED);
            player.displayClientMessage(
                    Component.literal("Авто-режим нагрузки: ").withStyle(ChatFormatting.GRAY).append(state), true);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // 2. Клик по баку: проверка, есть ли рядом двигатель
        if (CsFluid.pullTanksEnabled()) {
            IFluidHandler tankCap = level.getCapability(Capabilities.FluidHandler.BLOCK, clickedPos, event.getFace());
            if (tankCap == null) {
                tankCap = level.getCapability(Capabilities.FluidHandler.BLOCK, clickedPos, null);
            }

            if (tankCap != null) {
                List<AutoToggle> adjacentEngines = new ArrayList<>();
                for (Direction dir : Direction.values()) {
                    BlockEntity neighbor = level.getBlockEntity(clickedPos.relative(dir));
                    if (neighbor instanceof AutoToggle engineToggle) {
                        adjacentEngines.add(engineToggle);
                    }
                }

                if (!adjacentEngines.isEmpty()) {
                    boolean nowAllowed = false;
                    for (AutoToggle engineToggle : adjacentEngines) {
                        nowAllowed = engineToggle.csfToggleTank(clickedPos);
                        ((BlockEntity) engineToggle).setChanged();
                    }

                    Component state = Component.literal(nowAllowed ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН")
                            .withStyle(nowAllowed ? ChatFormatting.GREEN : ChatFormatting.RED);
                    player.displayClientMessage(
                            Component.literal("Забор лавы из бака: ").withStyle(ChatFormatting.GRAY).append(state), true);

                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                }
            }
        }
    }
}
