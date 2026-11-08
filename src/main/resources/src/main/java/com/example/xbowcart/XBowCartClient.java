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
            aimAt(p, cart.getPos().add(0, 0.4, 0));
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            msg(p, "Выстрел из арбалета");
        } else if (useF) {
            p.getInventory().setSelectedSlot(flint);
            mc.interactionManager.interactEntity(p, cart, Hand.MAIN_HAND);
            msg(p, "Огниво");
        } else if (useL) {
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
