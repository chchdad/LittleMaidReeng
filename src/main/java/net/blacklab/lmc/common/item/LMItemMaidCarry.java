package net.blacklab.lmc.common.item;

import java.util.List;

import javax.annotation.Nullable;

import net.blacklab.lmc.common.entity.LMEntityItemAntiDamage;
import net.blacklab.lmc.common.helper.LittleMaidHelper;
import net.blacklab.lmr.config.LMRConfig;
import net.blacklab.lmr.entity.littlemaid.EntityLittleMaid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityEntryBuilder;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * Mob持ち運び用アイテム
 * @author computer
 *
 */
@EventBusSubscriber 
public class LMItemMaidCarry extends Item {

	@SubscribeEvent
	public static void registerEntities(RegistryEvent.Register<EntityEntry> event) {
		event.getRegistry().register(EntityEntryBuilder.create()
			.entity(LMItemMaidCarry.LMEntityItemMaidSafe.class)
			.id(new ResourceLocation("lmr", "maid_carry_safe_entity"), 114515)
			.name("maid_carry_safe_entity")
			.tracker(64, 20, true)
			.build());
	}

	/**
	 * コンストラクタ
	 */
	public LMItemMaidCarry() {
		
		this.setMaxStackSize(1);
		
		this.setCreativeTab(CreativeTabs.MISC);
	}
	
	/**
	 * 左クリックからのアイテム化
	 */
	@Override
	public boolean onLeftClickEntity(ItemStack stack, EntityPlayer player, Entity entity)
    {
		//メイドさんアイテム化
		return createMaidItemStack(stack, player, entity);
    }
	
	/**
	 * Shift＋右クリックからのアイテム化
	 */
	@Override
	public boolean itemInteractionForEntity(ItemStack stack, EntityPlayer playerIn, EntityLivingBase target, EnumHand hand)
    {
		//メイドさんアイテム化
		boolean ret = createMaidItemStack(stack, playerIn, target);
		if (ret && stack.hasTagCompound()) {
			playerIn.setHeldItem(hand, stack);			
		}
		return ret;
    }
	
	/**
	 * メイドさんを生成する
	 */
	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
		
		ItemStack stack = player.getHeldItem(hand);
		
		if (!stack.isEmpty() 
				&& stack.getItem() instanceof LMItemMaidCarry
				&& stack.hasTagCompound()) {
			
			BlockPos position = pos.offset(facing);
			double x = position.getX() + 0.5;
			double y = position.getY();
			double z = position.getZ() + 0.5;
			
			//メイドさんのスポーン
			LittleMaidHelper.spawnEntityFromItemStack(stack, worldIn, x, y, z);
			
			//Tag情報を初期化
			stack.setTagCompound(null);
			
			return EnumActionResult.SUCCESS;

		}
		
        return EnumActionResult.PASS;
    }
	
	
	@Override
	@SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn)
    {
		tooltip.add(TextFormatting.LIGHT_PURPLE + I18n.format("item.maid_carry.info"));
		
		if (stack.hasTagCompound()) {
			
			//OwnerId
			if (stack.getTagCompound().hasKey("maid_owner")) {
				tooltip.add("Owner : " + stack.getTagCompound().getString("maid_owner"));
			}
			//メイド名
			if (stack.getTagCompound().hasKey("maid_name")) {
				tooltip.add("Maid : " + stack.getTagCompound().getString("maid_name"));
			}
			
		}
    }
	
	/**
	 * 耐性EntityItemを利用する
	 */
	@Override
	public boolean hasCustomEntity(ItemStack stack) {
		return true;
	}
	
	/**
	 * 耐性EntityItemを生成する
	 */
	@Override
	@Nullable
    public Entity createEntity(World world, Entity location, ItemStack itemstack)
    {
		EntityItem entity = new LMEntityItemMaidSafe(world, location.posX, location.posY, location.posZ, itemstack);
		entity.setDefaultPickupDelay();

		entity.motionX = location.motionX;
		entity.motionY = location.motionY;
		entity.motionZ = location.motionZ;
		
		//発光設定
		if (LMRConfig.cfg_general_item_glowing) {
			entity.setGlowing(true);
		}
		
        return entity;
    }
	
	/**
	 * NBTタグを持つ場合にエフェクト表示
	 */
	@Override
	@SideOnly(Side.CLIENT)
    public boolean hasEffect(ItemStack stack)
    {
        return stack.hasTagCompound();
    }
	
	/**
	 * メイドさんをアイテム化
	 * @return
	 */
	public boolean createMaidItemStack(ItemStack stack, EntityPlayer player, Entity entity) {
	
		//メイドさんチェック
		if (!(entity instanceof EntityLittleMaid)) {
			return false;
		}
		
		EntityLittleMaid entityMaid = (EntityLittleMaid) entity;
		
		//契約メイドさんチェック
		if (!player.getUniqueID().equals(entityMaid.getMaidMasterUUID())) {
			return true;
		}
		
		//NBTがある場合は何もしない
		if (stack.hasTagCompound()) {
			return true;
		}
		
		//メイド用スポーン情報の書き込み
		LittleMaidHelper.getItemStackFromEntity(entityMaid, stack);
		
		//メイドさん消去
		entityMaid.setDead();
		
		return true;
	}

	/**
	 * 内置保命逻辑的自定义物品实体类
	 */
	public static class LMEntityItemMaidSafe extends EntityItem {
		
		public LMEntityItemMaidSafe(World worldIn, double x, double y, double z, ItemStack stack) {
			super(worldIn, x, y, z, stack);
		}
		
		public LMEntityItemMaidSafe(World worldIn) {
			super(worldIn);
		}
		
		private boolean isActualMaidContainer() {
			ItemStack stack = this.getItem();
			return !stack.isEmpty() && stack.hasTagCompound() && stack.getTagCompound().hasKey("maid_owner");
		}

		@Override
		public boolean attackEntityFrom(DamageSource source, float amount) {
			if (!this.world.isRemote && !this.isDead && isActualMaidContainer()) {
				if (source.isFireDamage() || source.isExplosion() || source == DamageSource.CACTUS || source == DamageSource.OUT_OF_WORLD) {
					triggerEmergencyEscape(this);
					this.setDead();
					return true;
				}
			}
			return super.attackEntityFrom(source, amount);
		}

		@Override
		public void onUpdate() {
			super.onUpdate();
			if (!this.world.isRemote && !this.isDead && isActualMaidContainer()) {
				if (this.isInLava() || this.isBurning() || this.posY < -10.0D) {
					triggerEmergencyEscape(this);
					this.setDead();
				}
			}
		}

		private void triggerEmergencyEscape(EntityItem entityItem) {
			World world = entityItem.world;
			ItemStack stack = entityItem.getItem();
			if (stack.isEmpty() || !stack.hasTagCompound()) return;

			double x = entityItem.posX;
			double y = Math.max(1.0D, entityItem.posY);
			double z = entityItem.posZ;

			// 1. 释放女仆
			LittleMaidHelper.spawnEntityFromItemStack(stack, world, x, y, z);

			// 2. 获取实体
			List<EntityLittleMaid> maids = world.getEntitiesWithinAABB(EntityLittleMaid.class, 
					new net.minecraft.util.math.AxisAlignedBB(x - 2.0D, y - 2.0D, z - 2.0D, x + 2.0D, y + 2.0D, z + 2.0D));
			
			if (!maids.isEmpty()) {
				EntityLittleMaid maid = maids.get(0);
				
				// 3. 切换为原地待命状态
				maid.setMaidWait(true);

				// 4. 同心圆雷达最近安全点搜索
				if (isDangerousLocation(world, new BlockPos(maid))) {
					BlockPos maidPos = new BlockPos(maid);
					BlockPos safePos = null;
					int maxRadius = 128;
					
					searchLoop:
					for (int r = 1; r <= maxRadius; r++) {
						for (int cx = -r; cx <= r; cx++) {
							for (int cz = -r; cz <= r; cz++) {
								if (Math.abs(cx) != r && Math.abs(cz) != r) continue;
								
								for (int cy = -r; cy <= r; cy++) {
									BlockPos checkPos = maidPos.add(cx, cy, cz);
									if (checkPos.getY() < 1 || checkPos.getY() >= world.getHeight() - 2) continue;
									
									if (world.isBlockLoaded(checkPos) && !isDangerousLocation(world, checkPos) && world.getBlockState(checkPos.down()).isTopSolid()) {
										safePos = checkPos;
										break searchLoop;
									}
								}
							}
						}
					}
					
					if (safePos != null) {
						if (maid.attemptTeleport(safePos.getX() + 0.5D, safePos.getY(), safePos.getZ() + 0.5D)) {
							world.playSound(null, maid.prevPosX, maid.prevPosY, maid.prevPosZ, 
									SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 1.0F, 1.0F);
						}
					}
				}

				// 5. 获取主人并发送精确的 GPS 通知 
				EntityPlayer player = null;
				if (maid.getOwner() instanceof EntityPlayer) {
					player = (EntityPlayer) maid.getOwner();
				} else if (maid.getMaidMasterUUID() != null) {
					player = world.getPlayerEntityByUUID(maid.getMaidMasterUUID());
				}

				if (player != null) {
					// 拔除维度的中文硬编码，复用在遗物里用到的本地化键值
					Object dimComponent;
					int dim = maid.dimension;
					if (dim == 0) {
					    dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.0");
					} else if (dim == -1) {
					    dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.-1");
					} else if (dim == 1) {
					    dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.1");
					} else {
					    dimComponent = new net.minecraft.util.text.TextComponentTranslation("message.lmr.dim.unknown", dim);
					}
					
					player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
							"message.lmreengaged.maid_safe_escape", 
							maid.getName(), 
							dimComponent, 
							(int)maid.posX, 
							(int)maid.posY, 
							(int)maid.posZ
					));
				}
			}
		}

		private boolean isDangerousLocation(World world, BlockPos pos) {
			if (pos.getY() < 1 || pos.getY() >= world.getHeight()) return true;
			IBlockState state = world.getBlockState(pos);
			if (state.getMaterial() == Material.LAVA || state.getMaterial() == Material.FIRE || state.isFullCube()) {
				return true;
			}
			IBlockState headState = world.getBlockState(pos.up());
			if (headState.getMaterial() == Material.LAVA || headState.getMaterial() == Material.FIRE || headState.isFullCube()) {
				return true;
			}
			return false;
		}
	}
	
}
