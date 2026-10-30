package com.example.autotreefarm;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.function.Predicate;

/** Client-side state machine: plant -> bone meal -> chop half with axe -> hoe the rest -> repeat. */
final class TreeFarmBot {
	private enum State { OFF, PLANT, GROW, CHEST_OPEN, CHEST_TAKE, CHOP_AXE, CLEAR_HOE }

	private static final double REACH = 4.4;
	private static final int CHEST_RANGE = 5;

	private State state = State.OFF;
	private int wait;
	private int timeout;
	private int boneUses;
	private int plantFails;
	private BlockPos saplingPos;
	private BlockPos chestPos;
	private List<BlockPos> axeTargets = new ArrayList<>();
	private BlockPos current;

	boolean isRunning() { return state != State.OFF; }

	void start(MinecraftClient c) {
		state = State.PLANT;
		wait = 0; plantFails = 0; boneUses = 0;
		AutoTreeFarmClient.msg(c, "Запущено (J — стоп)");
	}

	void stop(MinecraftClient c, String why) {
		state = State.OFF;
		if (c.player != null && c.currentScreen instanceof GenericContainerScreen) c.player.closeHandledScreen();
		AutoTreeFarmClient.msg(c, why);
	}

	void tick(MinecraftClient c) {
		if (state == State.OFF) return;
		ClientPlayerEntity p = c.player;
		if (p == null || c.world == null || c.interactionManager == null) { state = State.OFF; return; }
		if (wait > 0) { wait--; return; }
		try {
			switch (state) {
				case PLANT -> doPlant(c, p);
				case GROW -> doGrow(c, p);
				case CHEST_OPEN -> doChestOpen(c, p);
				case CHEST_TAKE -> doChestTake(c, p);
				case CHOP_AXE -> doBreak(c, p, true);
				case CLEAR_HOE -> doBreak(c, p, false);
				default -> { }
			}
		} catch (Exception e) {
			stop(c, "Ошибка: " + e);
		}
	}

	// ---------- inventory helpers ----------

	private boolean select(MinecraftClient c, ClientPlayerEntity p, Predicate<ItemStack> f) {
		for (int i = 0; i < 9; i++) {
			if (f.test(p.getInventory().getStack(i))) {
				p.getInventory().setSelectedSlot(i);
				return true;
			}
		}
		for (int i = 9; i < 36; i++) {
			if (f.test(p.getInventory().getStack(i))) {
				int hot = p.getInventory().getSelectedSlot();
				c.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, hot, SlotActionType.SWAP, p);
				wait = 2;
				return true;
			}
		}
		return false;
	}

	private boolean has(ClientPlayerEntity p, Predicate<ItemStack> f) {
		for (int i = 0; i < 36; i++) if (f.test(p.getInventory().getStack(i))) return true;
		return false;
	}

	private static boolean isSapling(ItemStack s) { return s.isIn(ItemTags.SAPLINGS); }
	private static boolean isBone(ItemStack s) { return s.isOf(Items.BONE_MEAL); }
	private static boolean isAxe(ItemStack s) { return s.isIn(ItemTags.AXES); }
	private static boolean isHoe(ItemStack s) { return s.isIn(ItemTags.HOES); }

	private static Vec3d eye(ClientPlayerEntity p) { return p.getEyePos(); }

	private boolean use(MinecraftClient c, ClientPlayerEntity p, BlockPos pos, Direction side) {
		Vec3d hit = Vec3d.ofCenter(pos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
		c.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, side, pos, false));
		p.swingHand(Hand.MAIN_HAND);
		return true;
	}

	// ---------- planting ----------

	private void doPlant(MinecraftClient c, ClientPlayerEntity p) {
		if (!has(p, TreeFarmBot::isSapling)) { stop(c, "Саженцы закончились. Готово!"); return; }
		if (!select(c, p, TreeFarmBot::isSapling)) return;
		if (wait > 0) return;
		BlockPos ground = findGround(c, p);
		if (ground == null) {
			if (++plantFails > 5) stop(c, "Не нашёл подходящий блок земли рядом");
			wait = 10;
			return;
		}
		use(c, p, ground, Direction.UP);
		saplingPos = ground.up();
		state = State.GROW;
		boneUses = 0;
		timeout = 0;
		wait = 6;
	}

	private BlockPos findGround(MinecraftClient c, ClientPlayerEntity p) {
		BlockPos base = p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -4; dx <= 4; dx++)
			for (int dy = -3; dy <= 2; dy++)
				for (int dz = -4; dz <= 4; dz++) {
					BlockPos g = base.add(dx, dy, dz);
					if (g.equals(base.down())) { /* under feet is fine */ }
					if (!c.world.getBlockState(g).isIn(BlockTags.DIRT)) continue;
					if (!c.world.getBlockState(g.up()).isAir()) continue;
					if (!c.world.getBlockState(g.up(2)).isAir()) continue;
					if (g.up().equals(p.getBlockPos()) || g.up().equals(p.getBlockPos().up())) continue;
					double d = eye(p).squaredDistanceTo(Vec3d.ofCenter(g));
					if (d > REACH * REACH) continue;
					if (d < bestD) { bestD = d; best = g; }
				}
		return best;
	}

	// ---------- growing ----------

	private void doGrow(MinecraftClient c, ClientPlayerEntity p) {
		BlockState st = c.world.getBlockState(saplingPos);
		if (st.getBlock() instanceof SaplingBlock) {
			if (!has(p, TreeFarmBot::isBone)) {
				state = State.CHEST_OPEN;
				chestPos = null;
				timeout = 0;
				return;
			}
			if (boneUses > 80) { stop(c, "Дерево не растёт (80 костной муки). Проверь место/свет"); return; }
			if (!select(c, p, TreeFarmBot::isBone)) return;
			if (wait > 0) return;
			use(c, p, saplingPos, Direction.UP);
			boneUses++;
			wait = 3;
			return;
		}
		if (st.isIn(BlockTags.LOGS)) {
			startChop(c, p);
			return;
		}
		// саженец пропал / ещё не появился
		if (++timeout > 20) {
			if (++plantFails > 5) { stop(c, "Саженец не посадился"); return; }
			state = State.PLANT;
		}
		wait = 3;
	}

	// ---------- chest with bone meal ----------

	private void doChestOpen(MinecraftClient c, ClientPlayerEntity p) {
		if (chestPos == null) {
			chestPos = findSignChest(c, p);
			if (chestPos == null) { stop(c, "Нет костной муки и не найден сундук с табличкой «костная мука» (в радиусе " + CHEST_RANGE + " бл.)"); return; }
			use(c, p, chestPos, Direction.UP);
			timeout = 0;
			wait = 4;
			return;
		}
		if (c.currentScreen instanceof GenericContainerScreen) { state = State.CHEST_TAKE; return; }
		if (++timeout > 30) { stop(c, "Не удалось открыть сундук (сервер не разрешил?)"); return; }
		wait = 2;
	}

	private void doChestTake(MinecraftClient c, ClientPlayerEntity p) {
		if (!(p.currentScreenHandler instanceof GenericContainerScreenHandler h)) { state = State.CHEST_OPEN; chestPos = null; return; }
		int chestSlots = h.getRows() * 9;
		boolean took = false;
		for (int i = 0; i < chestSlots; i++) {
			if (isBone(h.getSlot(i).getStack())) {
				c.interactionManager.clickSlot(h.syncId, i, 0, SlotActionType.QUICK_MOVE, p);
				took = true;
				break;
			}
		}
		p.closeHandledScreen();
		if (!took) { stop(c, "В сундуке «костная мука» нет костной муки"); return; }
		state = State.GROW;
		wait = 5;
	}

	private BlockPos findSignChest(MinecraftClient c, ClientPlayerEntity p) {
		BlockPos base = p.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -CHEST_RANGE; dx <= CHEST_RANGE; dx++)
			for (int dy = -CHEST_RANGE; dy <= CHEST_RANGE; dy++)
				for (int dz = -CHEST_RANGE; dz <= CHEST_RANGE; dz++) {
					BlockPos sp = base.add(dx, dy, dz);
					BlockEntity be = c.world.getBlockEntity(sp);
					if (!(be instanceof SignBlockEntity sign) || !signSaysBone(sign)) continue;
					for (Direction d : Direction.values()) {
						BlockPos cp = sp.offset(d);
						Block b = c.world.getBlockState(cp).getBlock();
						if (b != Blocks.CHEST && b != Blocks.TRAPPED_CHEST && b != Blocks.BARREL) continue;
						double dist = eye(p).squaredDistanceTo(Vec3d.ofCenter(cp));
						if (dist > 5.5 * 5.5) continue;
						if (dist < bestD) { bestD = dist; best = cp; }
					}
				}
		return best;
	}

	private static boolean signSaysBone(SignBlockEntity sign) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			sb.append(sign.getFrontText().getMessage(i, false).getString()).append(' ');
			sb.append(sign.getBackText().getMessage(i, false).getString()).append(' ');
		}
		return sb.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").contains("костная мука");
	}

	// ---------- chopping ----------

	private void startChop(MinecraftClient c, ClientPlayerEntity p) {
		List<BlockPos> logs = collect(c, saplingPos, 10, 40, s -> s.isIn(BlockTags.LOGS), 300);
		if (logs.isEmpty()) { state = State.PLANT; return; }
		int minY = logs.stream().mapToInt(BlockPos::getY).min().orElse(saplingPos.getY());
		int maxY = logs.stream().mapToInt(BlockPos::getY).max().orElse(minY);
		int half = minY + Math.max(1, (maxY - minY + 1) / 2);
		axeTargets = new ArrayList<>();
		axeTargets.addAll(logs);
		sortTargets(p, axeTargets);
		current = null;
		timeout = 0;
		state = State.CHOP_AXE;
	}

	/** axe=true: break lower half of logs with the axe; axe=false: leaves + remaining logs with the hoe. */
	private void doBreak(MinecraftClient c, ClientPlayerEntity p, boolean axe) {
		Predicate<ItemStack> tool = axe ? TreeFarmBot::isAxe : TreeFarmBot::isHoe;
		if (!has(p, tool)) { stop(c, axe ? "Нет топора в инвентаре" : "Нет мотыги в инвентаре"); return; }
		if (!select(c, p, tool)) return;
		if (wait > 0) return;

		if (current != null && c.world.getBlockState(current).isAir()) { current = null; timeout = 0; }
		if (current == null) {
			current = axe ? nextFrom(c, p, axeTargets) : nextHoeTarget(c, p);
			if (current == null) {
				c.interactionManager.cancelBlockBreaking();
				if (axe) {
					state = State.CLEAR_HOE;
				} else {
					state = State.PLANT;
					plantFails = 0;
					wait = 10;
				}
				return;
			}
		}
		if (++timeout > 200) { // слишком долго (например защита от ломания) — пропускаем блок
			if (axe) axeTargets.remove(current);
			skipped.add(current);
			current = null; timeout = 0;
			return;
		}
		Direction side = Direction.getFacing(eye(p).subtract(Vec3d.ofCenter(current)));
		c.interactionManager.updateBlockBreakingProgress(current, side);
		p.swingHand(Hand.MAIN_HAND);
	}

	private final Set<BlockPos> skipped = new HashSet<>();

	private BlockPos nextFrom(MinecraftClient c, ClientPlayerEntity p, List<BlockPos> list) {
		Iterator<BlockPos> it = list.iterator();
		while (it.hasNext()) {
			BlockPos b = it.next();
			if (c.world.getBlockState(b).isAir()) { it.remove(); continue; }
			if (inReach(p, b)) return b;
		}
		return null; // остальные вне досягаемости
	}

	private BlockPos nextHoeTarget(MinecraftClient c, ClientPlayerEntity p) {
		List<BlockPos> t = collect(c, saplingPos, 10, 40,
				s -> s.isIn(BlockTags.LEAVES), 600);
		t.removeIf(b -> skipped.contains(b) || !inReach(p, b));
		if (t.isEmpty()) { skipped.clear(); return null; }
		// сначала листва, потом брёвна
		t.sort(Comparator
				.comparingInt((BlockPos b) -> c.world.getBlockState(b).isIn(BlockTags.LEAVES) ? 0 : 1)
				.thenComparingDouble(b -> eye(p).squaredDistanceTo(Vec3d.ofCenter(b))));
		return t.get(0);
	}

	private static boolean inReach(ClientPlayerEntity p, BlockPos b) {
		return eye(p).squaredDistanceTo(Vec3d.ofCenter(b)) <= REACH * REACH;
	}

	private void sortTargets(ClientPlayerEntity p, List<BlockPos> l) {
		l.sort(Comparator.comparingInt(BlockPos::getY).thenComparingDouble(b -> eye(p).squaredDistanceTo(Vec3d.ofCenter(b))));
	}

	/** Blocks matching the filter within horizontal radius r and height h above/below start. */
	private List<BlockPos> collect(MinecraftClient c, BlockPos start, int r, int h, Predicate<BlockState> f, int limit) {
		List<BlockPos> out = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				for (int dy = -1; dy <= h; dy++) {
					BlockPos b = start.add(dx, dy, dz);
					if (f.test(c.world.getBlockState(b))) {
						out.add(b);
						if (out.size() >= limit) return out;
					}
				}
		return out;
	}
}
