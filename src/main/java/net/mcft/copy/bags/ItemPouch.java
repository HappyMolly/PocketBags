package net.mcft.copy.bags;

import java.util.Arrays;
import java.util.List;

import net.mcft.copy.bags.client.ICustomDurabilityBar;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvironmentInterface;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.fabricmc.fabric.api.tag.TagRegistry;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.tag.Tag;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;

@EnvironmentInterface(value = EnvType.CLIENT, itf = ICustomDurabilityBar.class)
public class ItemPouch extends Item implements IItemPickupSink, ICustomDurabilityBar {

	public static final ScreenHandlerType<PouchScreenHandler> SCREEN_HANDLER = ScreenHandlerRegistry
			.registerExtended(PocketBagsMod.POUCH_ID, PouchScreenHandler::new);

	public static final Tag<Item> POUCHABLE_TAG = TagRegistry.item(new Identifier(PocketBagsMod.MOD_ID, "pouchable"));

	public ItemPouch() {
		super(new Item.Settings().group(ItemGroup.MISC).maxCount(1));
	}

	public boolean collect(ServerPlayerEntity player, ItemStack pouch, ItemStack pickup) {
		if (!ItemPouch.isPouchableItem(pickup))
			return false;
		ItemStack contents = ItemPouch.getContents(pouch);
		if (ItemStack.areItemsEqual(contents, pickup) && ItemStack.areTagsEqual(contents, pickup)) {
			int maxCount = pickup.getMaxCount() * 9;
			if (contents.getCount() < maxCount) {
				int newCount = Math.min(maxCount, contents.getCount() + pickup.getCount());
				int diff = newCount - contents.getCount();
				contents.setCount(newCount);
				pickup.setCount(pickup.getCount() - diff);
				ItemPouch.setContents(pouch, contents);
				return true;
			}
		}
		return false;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient && user.isSneaking() && (hand == Hand.MAIN_HAND))
			user.openHandledScreen(new PouchScreenHandler.Factory(user));
		return super.use(world, user, hand);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		PlayerEntity player = context.getPlayer();
		ItemStack pouch = context.getStack();
		ItemStack contents = ItemPouch.getContents(pouch);
		if (contents.isEmpty())
			return ActionResult.PASS;
		ActionResult result = ActionResult.PASS;

		// Generate the order of block offsets to be used on.
		Vec3i[] order;
		if (player.isSneaking()) {
			// When sneaking, only place a single block.
			order = new Vec3i[] { Vec3i.ZERO };
		} else {
			order = new Vec3i[9];
			Direction facing = player.getHorizontalFacing();
			order[0] = Vec3i.ZERO;
			order[1] = facing.rotateYClockwise().getVector();
			order[2] = facing.rotateYCounterclockwise().getVector();
			order[3] = order[0].offset(facing, 1);
			order[4] = order[1].offset(facing, 1);
			order[5] = order[2].offset(facing, 1);
			order[6] = order[0].offset(facing, -1);
			order[7] = order[1].offset(facing, -1);
			order[8] = order[2].offset(facing, -1);
		}

		// Set currently held item to the pouch contents.
		ItemPouch.setStackInHand(player, context.getHand(), contents);

		try {
			// Use contents stack on all blocks in order.
			for (int i = 0; i < order.length; i++) {
				BlockPos pos = context.getBlockPos().add(order[i]);
				Vec3d hitPos = context.getHitPos().add(Vec3d.of(order[i]));
				BlockHitResult hit = new BlockHitResult(hitPos, context.getSide(), pos, context.hitsInsideBlock());
				ItemUsageContext newContext = new ItemUsageContext(player, context.getHand(), hit);
				if (contents.getItem().useOnBlock(newContext).isAccepted())
					result = ActionResult.SUCCESS;
				if (player.getStackInHand(context.getHand()).isEmpty())
					break;
			}
		} finally {
			// Update pouch contents and reset the held item to the pouch.
			ItemPouch.setContents(pouch, player.getStackInHand(context.getHand()));
			ItemPouch.setStackInHand(player, context.getHand(), pouch);
		}

		return result;
	}

	@Override
	public ActionResult useOnEntity(ItemStack pouch, PlayerEntity player, LivingEntity origEntity, Hand hand) {
		ItemStack contents = ItemPouch.getContents(pouch);
		if (contents.isEmpty())
			return ActionResult.PASS;
		ActionResult result = ActionResult.PASS;

		List<LivingEntity> entities;
		if (player.isSneaking()) {
			// When sneaking, only interact with the clicked entity.
			entities = Arrays.asList(origEntity);
		} else {
			// Find all entities of the same type (and adult state) in range.
			Box box = new Box(origEntity.getPos().subtract(1.5, 1.0, 1.5), origEntity.getPos().add(1.5, 1.0, 1.5));
			@SuppressWarnings("unchecked")
			EntityType<LivingEntity> type = (EntityType<LivingEntity>) origEntity.getType();
			entities = player.world.getEntitiesByType(type, box, e -> (e.isBaby() == origEntity.isBaby()));
		}

		// Set currently held item to the pouch contents.
		ItemPouch.setStackInHand(player, hand, contents);

		try {
			// Use contents on found entities.
			for (LivingEntity entity : entities) {
				ItemStack stack = player.getStackInHand(hand);
				if (entity.interact(player, hand).isAccepted()
						|| stack.getItem().useOnEntity(stack, player, entity, hand).isAccepted())
					result = ActionResult.SUCCESS;
				if (player.getStackInHand(hand).isEmpty())
					break;
			}
		} finally {
			// Update pouch contents and reset the held item to the pouch.
			ItemPouch.setContents(pouch, player.getStackInHand(hand));
			ItemPouch.setStackInHand(player, hand, pouch);
		}

		return result;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		CompoundTag tag = stack.getSubTag(PocketBagsMod.POUCH_ID.toString());
		if (tag == null)
			return;
		ItemStack contents = ItemStack.fromTag(tag);
		contents.setCount(tag.getInt("Count"));
		tooltip.add(new LiteralText(contents.getCount() + "x ").append(contents.getName())
				.setStyle(Style.EMPTY.withColor(Formatting.GRAY)));
	}

	public static boolean isPouchableItem(ItemStack stack) {
		return isPouchableItem(stack.getItem());
	}

	public static boolean isPouchableItem(Item item) {
		return POUCHABLE_TAG.contains(item);
	}

	public static ItemStack getContents(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof ItemPouch))
			return ItemStack.EMPTY;
		CompoundTag tag = stack.getSubTag(PocketBagsMod.POUCH_ID.toString());
		if (tag == null)
			return ItemStack.EMPTY;
		ItemStack contents = ItemStack.fromTag(tag);
		// fromTag reads a byte but we want an int to allow Count > 127.
		contents.setCount(tag.getInt("Count"));
		return contents;
	}

	public static void setContents(ItemStack stack, ItemStack contents) {
		if (stack.isEmpty() || !(stack.getItem() instanceof ItemPouch))
			throw new IllegalArgumentException("Specified stack is not an ItemPouch");
		String key = PocketBagsMod.POUCH_ID.toString();
		if (contents.isEmpty())
			stack.removeSubTag(key);
		else {
			CompoundTag tag = stack.getOrCreateSubTag(key);
			contents.toTag(tag);
			// Again, toTag writes a byte, but we want it to be an int.
			tag.putInt("Count", contents.getCount());
		}
	}

	private static void setStackInHand(PlayerEntity player, Hand hand, ItemStack stack) {
		if (hand == Hand.MAIN_HAND)
			player.inventory.setStack(player.inventory.selectedSlot, stack);
		else
			player.inventory.offHand.set(0, stack);
	}

	@Environment(EnvType.CLIENT)
	public float getCustomDurability(ItemStack stack) {
		ItemStack contents = ItemPouch.getContents(stack);
		if (contents.isEmpty())
			return Float.NaN;

		float count = contents.getCount();
		float maxCount = contents.getMaxCount() * 9;
		return count / maxCount;
	}

}
