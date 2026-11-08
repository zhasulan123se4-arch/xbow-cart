package com.example.xbowcart;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * X-Bow Cart: рельса -> TNT-вагонетка -> поджиг.
 * Поджиг: арбалет (заряженный, лучше с Огненной стрелой) / огниво / ведро лавы.
 * Предметы должны лежать в хотбаре. R — запуск, G — сменить способ поджига.
 */
public class XBowCartClient implements ClientModInitializer {

    // ---- настройки ----
    static final float SAFE_MIN_HEALTH = 10f;   // "Безопасный X-Bow": не запускать, если HP меньше
    static final boolean SAFE_MODE = true;
    static final int STEP_DELAY = 2;            // тиков между шагами

    enum Ignite { AUTO, CROSSBOW, FLINT, LAVA, NONE }
    enum Stage { IDLE, RAIL, CART, IGNITE }

    private static KeyBinding runKey;
    private static KeyBinding modeKey;
    private Stage stage = Stage.IDLE;
    private Ignite mode = Ignite.AUTO;
    private BlockPos base;   // блок, где стоит игрок (в нём будет рельса)
    private int wait;

    @Override
    public void onInitializeClient() {
        runKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.xbowcart.run", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, KeyBinding.Category.MISC));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.xbowcart.mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) { stage = Stage.IDLE; return; }

        while (modeKey.wasPressed()) {
            Ignite[] v = Ignite.values();
            mode = v[(mode.ordinal() + 1) % v.length];
            msg(p, "Поджиг: " + mode);
        }
        while (runKey.wasPressed()) {
            if (stage == Stage.IDLE) start(mc, p); else { stage = Stage.IDLE; msg(p, "Отменено"); }
        }
        if (stage == Stage.IDLE) return;
        if (wait > 0) { wait--; return; }

        switch (stage) {
            case RAIL -> {
                if (!mc.world.getBlockState(base).isOf(net.minecraft.block.Blocks.RAIL)) {
                    if (!useOnBlock(mc, p, Items.RAIL, base.down(), Direction.UP)) { fail(p, "Нет рельсы в хотбаре"); return; }
                }
                stage = Stage.CART; wait = STEP_DELAY;
            }
            case CART -> {
                if (!useOnBlock(mc, p, Items.TNT_MINECART, base, Direction.UP)) { fail(p, "Нет TNT-вагонетки в хотбаре"); return; }
                stage = Stage.IGNITE; wait = STEP_DELAY + 1;
            }
            case IGNITE -> {
                ignite(mc, p);
                stage = Stage.IDLE;
            }
            default -> stage = Stage.IDLE;
        }
    }

    private void start(MinecraftClient mc, ClientPlayerEntity p) {
        if (SAFE_MODE && p.getHealth() < SAFE_MIN_HEALTH) { msg(p, "Безопасный режим: мало HP"); return; }
        BlockPos feet = p.getBlockPos();
        if (!mc.world.getBlockState(feet.down()).isSideSolidFullSquare(mc.world, feet.down(), Direction.UP)) {
            msg(p, "Встань на твёрдый блок"); return;
        }
        base = feet;
        stage = Stage.RAIL;
        wait = 0;
    }

    private void ignite(MinecraftClient mc, ClientPlayerEntity p) {
        if (mode == Ignite.NONE) { msg(p, "Без поджига — вагонетка стоит"); return; }

        List<TntMinecartEntity> carts = mc.world.getEntitiesByClass(
                TntMinecartEntity.class, new Box(base).expand(2.5), e -> true);
        if (carts.isEmpty()) { msg(p, "Вагонетка не найдена"); return; }
        TntMinecartEntity cart = carts.get(0);

        int xbow = slotOf(p, Items.CROSSBOW);
        boolean xbowReady = xbow >= 0 && CrossbowItem.isCharged(p.getInventory().getStack(xbow));
        int flint = slotOf(p, Items.FLINT_AND_STEEL);
        int lava = slotOf(p, Items.LAVA_BUCKET);

        boolean useX = (mode == Ignite.CROSSBOW || mode == Ignite.AUTO) && xbowReady;
        boolean useF = (mode == Ignite.FLINT || (mode == Ignite.AUTO && !useX)) && flint >= 0;
        boolean useL = (mode == Ignite.LAVA || (mode == Ignite.AUTO && !useX && !useF)) && lava >= 0;

        if (useX) {
            p.getInventory().setSelectedSlot(xbow);
            aimAt(p, new Vec3d(cart.getX(), cart.getY() + 0.4, cart.getZ()));
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            msg(p, "Выстрел из арбалета");
        } else if (useF) {
            p.getInventory().setSelectedSlot(flint);
            mc.interactionManager.interactEntity(p, cart, Hand.MAIN_HAND);
            msg(p, "Огниво");
        } else if (useL) {
            // лава в соседний блок рядом с вагонеткой (менее надёжно, чем огниво)
            Direction d = p.getHorizontalFacing();
            BlockPos side = base.offset(d);
            p.getInventory().setSelectedSlot(lava);
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(side.down()).add(0, 0.5, 0), Direction.UP, side.down(), false);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
            msg(p, "Лава");
        } else {
            msg(p, "Нечем поджечь (арбалет/огниво/лава)");
        }
    }

    // ---- утилиты ----
    private boolean useOnBlock(MinecraftClient mc, ClientPlayerEntity p, Item item, BlockPos pos, Direction side) {
        int slot = slotOf(p, item);
        if (slot < 0) return false;
        p.getInventory().setSelectedSlot(slot);
        Vec3d hitVec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hitVec, side, pos, false));
        return true;
    }

    private int slotOf(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item)) return i;
        }
        return -1;
    }

    private void aimAt(ClientPlayerEntity p, Vec3d target) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
        p.setPitch((float) -(MathHelper.atan2(dy, h) * 180.0 / Math.PI));
    }

    private void fail(ClientPlayerEntity p, String text) { stage = Stage.IDLE; msg(p, text); }

    private void msg(ClientPlayerEntity p, String text) { p.sendMessage(Text.literal("[X-Bow Cart] " + text), true); }
}
